# 知识库系统改造方案：RAG + Function Calling 混合架构

> 实施日期：2026-06-08

## 需求概述

1. 景点基础介绍构建为 Document
2. 按语义切分 Chunk
3. Embedding 后存入 Qdrant
4. 景点价格、库存等实时数据不进入知识库
5. 采用 RAG + Function Calling 混合架构
6. 规则类问题走 RAG
7. 实时查询走业务接口

## 改动概览

| 类别 | 新增文件 | 修改文件 |
|------|---------|---------|
| 语义分块 | 2 个（Enricher + Splitter） | 1 个（SpotKnowledgeServiceImpl） |
| Function Calling | 3 个（Tools + Service 接口/实现） | 2 个（AIConfig + RagConfig） |
| 数据隔离 | 0 | 2 个（SpotDocumentRetriever + SpotQAServiceImpl） |
| 配置 | 0 | 1 个（application-ai.yaml） |

---

## Phase 1: 语义分块（Chunking）

### 1.1 `SpotKnowledgeEnricher`
**文件**: `tourmind-ai/src/main/java/com/hmdp/rag/chunking/SpotKnowledgeEnricher.java`

- 将 Spot 实体转为带语义标签的结构化文本
- 示例输出：

```
[景点简介]
景点名称：西湖
景点类型：自然风景区
评分：4.5/5.0分

[位置交通]
所在区域：西湖区
具体地址：杭州市西湖区西湖风景区

[开放须知]
开放时间：全天
```

- `score` 转换：整数 45 → "4.5/5.0分"
- **不包含** ticketPrice（价格走 Function Calling）

### 1.2 `SpotKnowledgeSplitter`
**文件**: `tourmind-ai/src/main/java/com/hmdp/rag/chunking/SpotKnowledgeSplitter.java`

- 按 `\n(?=\[)` 正则切分（语义标题前分割）
- 每个 chunk 继承 spotId/spotName 元数据 + chunkTopic/chunkIndex
- 无标题时返回原 Document（向后兼容）

### 1.3 修改 `SpotKnowledgeServiceImpl`
- initialize 流程: enrich → Document → split → vectorStore.add(chunks)
- Redis 仍存完整富化文本
- delete/update 无需改动

---

## Phase 2: 实时数据隔离

- `SpotKnowledgeEnricher`: 不生成 ticketPrice
- `SpotDocumentRetriever.buildSpotContent()`: 移除价格行
- `SpotQAServiceImpl.buildContextFromSpots()`: 移除价格行

---

## Phase 3: Function Calling

### 3.1 `SpotVoucherMapper`
**文件**: `tourmind-ai/src/main/java/com/hmdp/mapper/SpotVoucherMapper.java`

`@Select` 注解 Mapper，查询 tb_voucher + tb_seckill_voucher。

### 3.2 Tool Service
- `ISpotToolService`: getSpotPrice / getSpotVouchers / checkVoucherStock
- `SpotToolServiceImpl`: 注入 SpotMapper + SpotVoucherMapper

### 3.3 `SpotTools`
**文件**: `tourmind-ai/src/main/java/com/hmdp/tool/SpotTools.java`

使用 `FunctionCallback` 暴露 3 个函数给 LLM。

### 3.4 修改 `AIConfig`
`chatClient` Bean 添加 `.defaultTools(...)`。

### 3.5 修改 System Prompt
告知 LLM 可调用工具获取实时数据。

---

## 新增/修改文件清单

### 新增 (6 个)
1. `rag/chunking/SpotKnowledgeEnricher.java`
2. `rag/chunking/SpotKnowledgeSplitter.java`
3. `mapper/SpotVoucherMapper.java`
4. `tool/SpotTools.java`
5. `service/ISpotToolService.java`
6. `service/impl/SpotToolServiceImpl.java`

### 修改 (6 个)
1. `config/AIConfig.java` — 注册 tools
2. `config/RagConfig.java` — 更新 systemPrompt
3. `service/impl/SpotKnowledgeServiceImpl.java` — 集成 enricher + splitter
4. `rag/retrieval/SpotDocumentRetriever.java` — 移除 price
5. `service/impl/SpotQAServiceImpl.java` — 移除 price
6. `resources/application-ai.yaml` — 更新 system-prompt

### 新增测试 (4 个)
1. `SpotKnowledgeEnricherTest.java`
2. `SpotKnowledgeSplitterTest.java`
3. `SpotToolServiceTest.java`
4. `SpotRagWithFunctionCallingTest.java` (集成测试)

---

## 验证

```bash
mvn clean compile -f pom.xml
mvn test -pl tourmind-ai -am
mvn test -pl tourmind-ai -am -Dgroups=integration
```

手动验证：
- "西湖有什么好玩的？" → RAG（介绍类）
- "西湖门票多少钱？" → Function Calling
- "雷峰塔有优惠券吗？" → Function Calling
