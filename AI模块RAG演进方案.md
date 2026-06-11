# AI 模块 RAG → Agentic RAG 演进方案

## 一、当前 RAG 架构定位

当前实现是**高级模块化 RAG + CRAG（Corrective RAG）**，管线如下：

```
用户Query → QueryRouter(规则分流) → Compression(指代消解) → Rewrite(关键词改写)
  → HybridRetrieve(ES BM25 + Qdrant向量 + RRF融合 + 可选Rerank)
  → CRAG评估(分数阈值) → ContextualQueryAugmenter → LLM生成 + Function Calling
```

**已有能力**：
- 混合检索（稠密向量 + 稀疏 BM25 + RRF 排名融合）
- 自适应分流（闲聊/知识查询/退购票三路）
- Query 重写（口语→关键词，提升召回率）
- 多轮对话指代消解（CompressionQueryTransformer）
- CRAG 检索质量自评（CONFIDENT/AMBIGUOUS/INSUFFICIENT）
- Function Calling（价格/优惠券/库存/天气实时查询）
- Parent-Child 文档索引（父文档全文 + 子文档分块检索）
- 可选 Rerank（qwen3-rerank 精排）

## 二、与 Agentic RAG 的核心差距

当前系统本质是**固定流程的单次检索**——工程师预设了所有决策路径。Agentic RAG 的核心差异是：**LLM Agent 自己决定走哪条路**。

| # | 能力维度 | 当前状态 | 差距等级 |
|---|---------|---------|---------|
| 1 | **Agentic 决策循环** | ❌ 硬编码固定管线（Compress→Rewrite→Retrieve→Augment） | 🔴 核心 |
| 2 | **自反思/重检索** | ⚠️ CRAG 仅评估，INSUFFICIENT 只追加免责声明，不重试 | 🔴 核心 |
| 3 | **复杂问题分解** | ❌ 单 query 单次检索，无法拆分为子问题 | 🔴 核心 |
| 4 | 多源动态路由 | ⚠️ QueryRouter 基于关键词规则匹配，非语义决策 | 🟡 需升级 |
| 5 | 多跳推理 | ⚠️ 通过 Function Calling 间接支持，非显式串联 | 🟡 需增强 |
| 6 | 检索-工具统一编排 | ⚠️ RAG 检索和 Function Calling 时序隔离 | 🟡 需融合 |
| 7 | 答案验证/忠实度检查 | ❌ 生成后无验证步骤 | 🔴 未实现 |
| 8 | 自适应检索参数 | ❌ topK、阈值等全为静态 YAML 配置 | 🟡 可优化 |
| 9 | 知识图谱推理 | ❌ 无 | 🟢 远期 |
| 10 | 对话级检索规划 | ❌ ChatMemory 仅存对话历史 | 🟡 可优化 |

### 三个最关键的缺失

1. **迭代循环能力**：当前单次检索→单次生成，Agentic RAG 是多轮 Thought→Action→Observation 循环
2. **检索失败自纠正**：CRAG 评分后只追加免责声明，从不重新检索
3. **复杂问题分解**："西湖和雷峰塔哪个更好？价格差多少？"这类复合 query 无法分治

## 三、Phase 1 实现方案：ReACT Agent Shell

### 设计原则

- **零侵入**：现有 `/ai/spot/question` 端点行为完全不变
- **渐进式**：新增 `/ai/spot/question/agent` 端点，可灰度对比
- **复用优先**：HybridDocumentRetriever、QueryRouter、SpotTools 等全部复用

### 架构

```
SpotQAController
  │
  ├─ POST /ai/spot/question         → answerSpotQuestion()     [不改]
  │
  └─ POST /ai/spot/question/agent   → answerSpotQuestionAgent() [新增]
       │
       └─ ReActAgentLoop.thinkAndAct()
            │
            ├─ 构建消息列表（系统提示 + ChatMemory历史 + 用户问题）
            ├─ 注册工具定义（searchKnowledgeBase / getSpotPrice / ...）
            ├─ ┌─ 循环 ──────────────────────────────┐
            │   │ ChatClient → ChatResponse             │
            │   │ 有 tool_calls？ → 手动执行工具        │
            │   │   追加 ToolResponseMessage → 继续循环  │
            │   │ 无 tool_calls？→ 文本即最终回答 → 退出 │
            │   └────────────────────────────────────┘
            └─ 更新 ChatMemory → 返回 AgentResult
```

### 关键设计决策

**为什么手动循环而不是用 ChatClient 自动工具调用？**

Spring AI 1.0.7 的 ChatClient 内部工具执行是黑盒，无法在每步：
- 注入置信度反馈（引导 LLM 决策是否重搜）
- 记录 AgentTrace（调试和可观测性）
- 控制最大迭代次数

设置 `internalToolExecutionEnabled=false` + 手动循环获得完整的可观察性和控制力。

### 工具定义

| 工具名 | 功能 | 参数 |
|--------|------|------|
| searchKnowledgeBase | 检索景点知识库 | query: 关键词串 |
| getSpotPrice | 查询门票价格 | spotId: 整数 |
| getSpotVouchers | 查询优惠券 | spotId: 整数 |
| checkVoucherStock | 检查库存 | voucherId: 整数 |
| checkWeather | 查询天气 | location, lat?, lon? |

### 检索结果置信度反馈

searchKnowledgeBase 的执行结果包含明确的行动建议：

```
[检索结果] 找到 5 篇文档 | 置信度: CONFIDENT | 最高分: 0.87
--- 文档1 (spotId=42) ---
景点名称：雷峰塔 | 类型：文化古迹 | 评分：4.3/5.0 | 区域：西湖区
...

[建议] 结果置信度高，可直接用于回答用户问题。如需价格/天气，请调用对应工具。
```

INSUFFICIENT 时则建议换关键词重搜或加免责声明。

### 新增/修改文件清单

| 类型 | 文件 | 说明 |
|------|------|------|
| 新增 | `agent/AgentStep.java` | 单步记录数据类 |
| 新增 | `agent/AgentTrace.java` | 步骤聚合 |
| 新增 | `agent/AgentResult.java` | 执行结果 Record |
| 新增 | `agent/SearchKnowledgeBaseTool.java` | 检索工具封装 |
| 新增 | `agent/ReActAgentLoop.java` | **核心**：ReACT 循环 |
| 新增 | `agent/SearchKnowledgeBaseToolTest.java` | 检索工具测试 |
| 新增 | `agent/ReActAgentLoopTest.java` | Agent 循环测试 |
| 修改 | `config/RagConfig.java` | +AgentConfig 配置类 |
| 修改 | `service/ISpotQAService.java` | +answerSpotQuestionAgent() |
| 修改 | `service/impl/SpotQAServiceImpl.java` | +Agent 模式实现 |
| 修改 | `controller/SpotQAController.java` | +POST /question/agent |
| 修改 | `application-ai.yaml` | +rag.agent 配置节 |
| 扩展 | `SpotQAServiceImplTest.java` | +AnswerSpotQuestionAgentTests |

### 未修改（零回归风险）

AIConfig.java、HybridDocumentRetriever.java、SpotTools.java、WeatherTools.java、QueryRouter.java、RetrievalEvaluator.java

## 四、Phase 1 已解决的问题

| 差距 | 解决程度 | 机制 |
|------|:---:|------|
| ① 迭代循环 | ✅ 完全解决 | ReACT 手动循环，LLM 自主决定每步动作 |
| ② 自纠正检索 | ⚠️ 架构就绪 | 检索结果含置信度+行动建议，LLM 可决定重搜 |
| ③ 复杂问题分解 | ⚠️ 顺序分解 | LLM 可顺序执行多个子任务，但并行分解需 Phase 3 |

## 五、后续演进路线

### Phase 2：自纠正检索增强
- 将 CRAG 评估信号由"建议文字"升级为 Agent 可感知的量化反馈
- Agent 自动在 INSUFFICIENT 后调整 query 参数重新检索
- 引入检索次数上限和 progressively relaxed 策略

### Phase 3：Plan-and-Execute 复合问题分解
- 对识别为"复合问题"的 query 启用 Planner
- 并行执行多个独立子检索
- 综合结果后生成回答

### Phase 4：答案验证 / 忠实度检查
- 生成回答后逐条验证声明是否可追溯到检索结果
- 发现不一致时自动修正
