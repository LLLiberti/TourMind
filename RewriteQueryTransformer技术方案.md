# RewriteQueryTransformer 技术方案

> 实施日期：2026-06-09

## 背景

当前 RAG 检索管线中，用户自然语言 query（如"西湖有啥好玩的推荐一下呗"）包含大量口语噪音，与知识库中结构化 chunk 文本（`[景点简介]\n景点名称：西湖\n...`）的 embedding 相似度偏低，导致向量检索召回率不足。

## 方案设计

### 核心思路

在 `CompressionQueryTransformer`（多轮指代消解）**之前**插入 `RewriteQueryTransformer`，用 LLM 将口语 query 改写为 **10-20 个空格分隔的关键词串**，提升与知识库 chunk 的向量相似度。

### Pipeline

```
用户问题："西湖有啥好玩的推荐一下呗"
     │
     ▼
[RewriteQueryTransformer]  ← 新增
     │  输出："西湖 自然风景区 游览 推荐 好玩 必去 景点 杭州 名胜 观光"
     ▼
[CompressionQueryTransformer]  ← 已有（多轮指代消解）
     │
     ▼
[SpotDocumentRetriever] → 向量检索
     │
     ▼
[ContextualQueryAugmenter] → 注入 LLM 上下文
```

### 为什么用关键词而非完整句子

| 方式 | embedding 与 chunk 相似度 | 风险 |
|------|---------------------------|------|
| 完整问句 | 低（含大量噪音词） | — |
| 关键词串 | **高**（与 chunk 同为结构化文本） | LLM 可能引入无关词 |

### LLM Prompt

```
将用户问题转换为用于向量检索的关键词串。
规则：
- 提取问题中的核心实体（景点名、地名、类型等）
- 扩展语义相关的同义词、关联词（如"好玩"→"好玩 推荐 必去 有趣 游览"）
- 问题中提到的实体名称必须保留在原样
- 输出仅包含空格分隔的关键词，不要有任何解释和标点
- 关键词控制在 10-20 个

用户问题：{question}
输出：
```

### 降级策略

| 场景 | 处理方式 |
|------|---------|
| query ≤ 5 字符 | 跳过改写，直接透传 |
| LLM 调用异常 | 回退原始 query，不阻塞检索 |
| 改写结果 < 3 字符 | 回退原始 query |

## 文件清单

| 文件 | 操作 | 说明 |
|------|------|------|
| `tourmind-ai/.../rag/query/RewriteQueryTransformer.java` | 新增 | 实现 `QueryTransformer`，调用 DeepSeek 改写 query |
| `tourmind-ai/.../config/AIConfig.java` | 修改 | 新增 `rewriteQueryTransformer` Bean，插入 queryTransformers 链 |

## 关键实现

```java
public class RewriteQueryTransformer implements QueryTransformer {

    private final ChatModel chatModel;

    @Override
    public Query transform(Query query) {
        String originalText = query.text();
        // 短 query 跳过改写
        if (originalText.trim().length() <= 5) return query;

        try {
            String rewritten = rewriteToKeywords(originalText);
            return query.mutate().text(rewritten).build();
        } catch (Exception e) {
            // 降级：回退原始 query
            return query;
        }
    }

    private String rewriteToKeywords(String question) {
        // 构建 prompt → ChatModel.call() → 返回关键词串
    }
}
```

## 性能影响

- LLM 调用增加 1 次/turn（DeepSeek API 轻量请求，约 100-200 tokens）
- 改写成功 → 检索召回率提升 → 回答质量提升
- 降级路径保证可用性不受影响

## 验证结果

```
mvn compile -pl tourmind-ai -am  → BUILD SUCCESS
mvn test -pl tourmind-ai -am     → BUILD SUCCESS (52 tests passed)
```
