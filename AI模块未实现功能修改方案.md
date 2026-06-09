# AI模块未实现功能修改方案

## 一、现状与目标

### 已实现（无需修改）
| 功能 | 实现位置 |
|------|---------|
| Query层：DeepSeek轻量模型口语→关键词转换 | `rag/query/RewriteQueryTransformer.java` |
| 多轮对话指代消解 | `AIConfig.HistoryEnrichedQueryTransformer` + `CompressionQueryTransformer` |
| Function Calling：票价/优惠券/库存实时查询 | `tool/SpotTools.java` + `service/impl/SpotToolServiceImpl.java` |

### 未实现（本方案范围）
| 层级 | 功能 | 说明 |
|------|------|------|
| 切片层 | Parent-Child索引策略 | 父文档存全文，子文档按`[Section]`切分；检索子文档，返回父文档 |
| 索引层 | BM25关键词倒排索引 | 内存BM25索引，与Qdrant向量索引互补 |
| 召回层 | 动态混合检索 | 向量+BM25双路召回，根据query特征动态分配权重 |
| 重排层 | qwen3-rerank两阶段精排 | 粗召回→精排序→分数阈值过滤 |

---

## 二、目标架构

### 修改后检索管线

```
用户问题
  → MessageChatMemoryAdvisor (对话记忆)
  → RetrievalAugmentationAdvisor:
      CompressionQueryTransformer (指代消解)
      → RewriteQueryTransformer (口语→关键词)
      → HybridDocumentRetriever (替换 SpotDocumentRetriever):
          ① Qdrant向量检索（仅检索 docType="child" 的子chunk）
          ② BM25关键词检索（内存倒排索引）
          ③ ScoreNormalizer 分数归一化 + QueryAnalyzer 动态权重融合
          ④ QwenRerankClient 两阶段精排 + 阈值过滤
          → 提取 spotId → DB批量查询 → 距离排序
          → 加载父文档全文(Redis→MySQL→重建) → 返回Documents
      → ContextualQueryAugmenter (注入LLM上下文)
  → SimpleLoggerAdvisor
  → DeepSeek LLM + SpotTools Function Calling
```

### 修改后索引管线

```
initializeSpotKnowledge(spotId):
  Spot实体 → SpotKnowledgeEnricher.enrich() → 结构化全文
  → ParentChildIndexer.indexSpot():
      父Document(docType="parent", 全文本) → Qdrant向量化
      子Documents(docType="child", 按[Section]切分) → Qdrant向量化
      子文本 → bm25Index.addDocument()
      父全文 → MySQL tb_spot_knowledge (持久化)
      父全文 → Redis spot:knowledge:parent:{spotId} (缓存)
```

### 父文档查询路径

```
加载父文档全文(spotId):
  ① Redis spot:knowledge:parent:{spotId} → 命中返回
  ② MySQL tb_spot_knowledge WHERE spot_id=? → 命中则回写Redis，返回
  ③ SpotMapper.selectById(spotId) → SpotKnowledgeEnricher.enrich()
    → 写入MySQL + 回写Redis → 返回
```

---

## 三、新建文件清单

### 3.1 源码文件（8个）

#### `tourmind-common/src/main/java/com/hmdp/entity/SpotKnowledge.java`
MyBatis-Plus实体，映射新表 `tb_spot_knowledge`：
```java
@TableName("tb_spot_knowledge")
public class SpotKnowledge implements Serializable {
    @TableId(value = "spot_id", type = IdType.INPUT)
    private Long spotId;
    private String knowledgeText;    // 富化后的完整知识文本（父文档）
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
```

#### `tourmind-common/src/main/java/com/hmdp/mapper/SpotKnowledgeMapper.java`
MyBatis-Plus BaseMapper：
```java
public interface SpotKnowledgeMapper extends BaseMapper<SpotKnowledge> {
}
```

#### `tourmind-ai/src/main/java/com/hmdp/rag/index/Bm25InvertedIndex.java`
内存BM25倒排索引，线程安全，无外部依赖。

核心数据结构：
- 倒排索引：`ConcurrentHashMap<String, List<PostingEntry>>` — term → (docId, termFreq)
- 文档存储：`ConcurrentHashMap<String, IndexedDocument>` — docId → (text, length, metadata)
- 统计：`AtomicInteger totalDocs`, `AtomicLong totalLength`

BM25公式：`score(D,Q) = Σ IDF(t) × tf×(k1+1) / (tf + k1×(1-b+b×|D|/avgdl))`

中文分词策略（字符bigram）：
- 中文字符序列 → 相邻字符bigram（如"西湖风景" → ["西湖","湖风","风景"]）
- 连续ASCII字符 → 整体保留为单词
- 混合内容各自处理，结果合并

API：
```java
void addDocument(String docId, String text, Map<String, Object> metadata);
void removeDocument(String docId);
List<ScoredResult> search(String query, int topK);  // ScoredResult{docId, score, text, metadata}
void clear();
int totalDocuments();
```

#### `tourmind-ai/src/main/java/com/hmdp/rag/index/ParentChildIndexer.java`
父子文档索引协调器，封装父文档创建、子文档分块、Qdrant写入、BM25更新、MySQL持久化。

元数据设计：
| 字段 | 父文档 | 子文档 |
|------|--------|--------|
| spotId | "123" | "123" |
| spotName | "西湖" | "西湖" |
| docType | "parent" | "child" |
| chunkTopic | — | "简介" |
| chunkIndex | — | 0 |

API：
```java
ParentChildIndexer(VectorStore, Bm25InvertedIndex, SpotKnowledgeMapper,
                   RedisTemplate, SpotKnowledgeEnricher, SpotKnowledgeSplitter);
IndexingResult indexSpot(Spot spot, String typeName);  // 全量索引一个景点
void deleteSpot(Long spotId);                           // 清理Qdrant + BM25 + MySQL + Redis
String loadParentText(Long spotId);                      // Redis → MySQL → 重建
```

#### `tourmind-ai/src/main/java/com/hmdp/rag/retrieval/HybridDocumentRetriever.java`
**替换** `SpotDocumentRetriever`，实现 `DocumentRetriever` 接口。

保留原公开API（与 `SpotQAServiceImpl` 兼容）：
```java
void setUserCoordinates(Double userX, Double userY);
List<Spot> getLastRetrievedSpots();
void clearContext();
```

`retrieve(Query query)` 流程：
```
① Qdrant向量检索: similaritySearch(query, topK=20, filter="docType=='child'")
   按spotId去重，保留最高分
② BM25检索: bm25Index.search(query, topK=20)
③ QueryAnalyzer分析query特征 → 动态权重 w
④ ScoreNormalizer: minMax归一化 → fuse(vector, bm25, w) → 排序取topK
⑤ [reranker.enabled] QwenRerankClient.rerank(originalQuery, docs, topK) → 阈值过滤
⑥ 提取spotId → SpotMapper.selectBatchIds() → 距离排序
⑦ ThreadLocal缓存Spot列表（供Service构建DTO）
⑧ ParentChildIndexer.loadParentText(spotId) 逐个加载父文档全文
⑨ 返回 List<Document> 给 ContextualQueryAugmenter
```

#### `tourmind-ai/src/main/java/com/hmdp/rag/retrieval/ScoreNormalizer.java`
分数归一化与融合，线程安全纯函数。

API：
```java
List<ScoredResult> minMaxNormalize(List<ScoredResult> results);
List<FusedResult> fuse(List<ScoredResult> vector, List<ScoredResult> bm25,
                        double vectorWeight, int topK);
```
- minMax归一化：`(score-min)/(max-min)`，max==min时返回1.0
- 融合：按spotId归并，`final = w×normVec + (1-w)×normBM25`，仅一侧出现则另一侧0

#### `tourmind-ai/src/main/java/com/hmdp/rag/retrieval/QueryAnalyzer.java`
查询特征分析，无状态纯函数。

动态权重策略：
- 空格分隔词数 ≥8 → 关键词倾向 → vectorWeight = max(0.3, defaultWeight - 0.03×(termCount-5))
- 空格分隔词数 ≤3 → 语义倾向 → vectorWeight = min(0.9, defaultWeight + 0.05×(4-termCount))
- 其他 → vectorWeight = defaultWeight（默认0.6）

#### `tourmind-ai/src/main/java/com/hmdp/rag/client/QwenRerankClient.java`
qwen3-rerank HTTP客户端，使用RestTemplate。

请求格式：
```json
POST {endpoint}/rerank
{"query": "原始问题", "documents": ["文档1", "文档2"], "top_n": 10, "model": "qwen3-rerank"}
```
响应格式：
```json
{"results": [{"index": 0, "relevance_score": 0.95}, {"index": 1, "relevance_score": 0.23}]}
```
- 超时通过 `reranker.timeout-ms` 配置，默认5000ms
- 异常时记录日志并返回null（调用方跳过重排步骤）
- 分数阈值过滤：仅保留 `relevance_score >= reranker.score-threshold` 的结果

### 3.2 测试文件（6个）

> **测试原则**：使用业务环境而非mock。对于纯逻辑类用真实实例直接测试；对于依赖Spring的类用 `@SpringBootTest` + 项目已有的test配置。

| 测试类 | 被测类 | 方式 |
|--------|--------|------|
| `Bm25InvertedIndexTest` | Bm25InvertedIndex | 纯逻辑，直接实例化测试。覆盖：空索引搜索、添加/删除文档、BM25分数计算验证（已知值对比）、中文bigram分词正确性、并发读安全、score排序 |
| `ScoreNormalizerTest` | ScoreNormalizer | 纯逻辑，直接实例化测试。覆盖：minMax归一化（均匀分/极端值/单元素）、融合（交集/仅向量/仅BM25/空列表） |
| `QueryAnalyzerTest` | QueryAnalyzer | 纯逻辑，直接实例化测试。覆盖：关键词查询权重、短查询权重、默认权重、边界值 |
| `ParentChildIndexerTest` | ParentChildIndexer | `@SpringBootTest`，注入VectorStore/Redis/MySQL。覆盖：索引单景点→验证Qdrant含父子文档+BM25可搜+MySQL有记录；deleteSpot→验证全部清理 |
| `QwenRerankClientTest` | QwenRerankClient | `@SpringBootTest`，需配置reranker endpoint。覆盖：成功调用解析、超时处理、阈值过滤、空文档列表 |
| `HybridDocumentRetrieverTest` | HybridDocumentRetriever | `@SpringBootTest`（`@Tag("integration")`）。覆盖：向量+BM25混合检索、重排、动态权重生效、空结果、ThreadLocal清理 |

### 3.3 SQL文件

#### `tourmind-core/src/main/resources/db/spot_knowledge.sql`
```sql
DROP TABLE IF EXISTS `tb_spot_knowledge`;
CREATE TABLE `tb_spot_knowledge` (
  `spot_id` bigint(20) UNSIGNED NOT NULL COMMENT '景点id',
  `knowledge_text` text NOT NULL COMMENT '富化后的完整知识文本（父文档）',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`spot_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
```

---

## 四、修改文件清单

### 4.1 `config/RagConfig.java` — 新增嵌套配置类

在现有字段后追加（无enabled开关，这些功能始终启用；仅reranker保留开关）：

```java
/** Parent-Child 索引配置 */
private ParentChildConfig parentChild = new ParentChildConfig();

/** BM25 关键词检索配置 */
private Bm25Config bm25 = new Bm25Config();

/** 混合检索配置 */
private HybridConfig hybrid = new HybridConfig();

/** 重排配置（需外部qwen3-rerank服务） */
private RerankerConfig reranker = new RerankerConfig();

@Data
public static class ParentChildConfig {
    // 预留扩展，当前无需额外参数
}

@Data
public static class Bm25Config {
    private double k1 = 1.2;
    private double b = 0.75;
    private int topK = 20;
}

@Data
public static class HybridConfig {
    private int retrievalCandidates = 20;
    private double defaultVectorWeight = 0.6;
    private String normalization = "minmax";  // "minmax" | "rank"
}

@Data
public static class RerankerConfig {
    private boolean enabled = false;         // 仅此项保留开关
    private String endpoint = "";
    private String model = "qwen3-rerank";
    private int topK = 10;
    private double scoreThreshold = 0.1;
    private int timeoutMs = 5000;
}
```

### 4.2 `config/AIConfig.java` — Bean装配调整

**替换Bean**：
```java
// 原: SpotDocumentRetriever → 新: HybridDocumentRetriever
@Bean
public HybridDocumentRetriever spotDocumentRetriever(
        VectorStore vectorStore,
        SpotMapper spotMapper,
        SpotTypeMapper spotTypeMapper,
        RagConfig ragConfig,
        Bm25InvertedIndex bm25Index,
        ParentChildIndexer parentChildIndexer,
        QwenRerankClient rerankClient) {  // may be null
    return new HybridDocumentRetriever(vectorStore, spotMapper, spotTypeMapper,
            ragConfig, bm25Index, parentChildIndexer, rerankClient);
}
```

**新增Bean**（始终创建，不使用@ConditionalOnProperty）：
```java
@Bean
public Bm25InvertedIndex bm25InvertedIndex(RagConfig ragConfig) {
    return new Bm25InvertedIndex(ragConfig.getBm25());
}

@Bean
public ParentChildIndexer parentChildIndexer(
        VectorStore vectorStore, Bm25InvertedIndex bm25Index,
        SpotKnowledgeMapper spotKnowledgeMapper,
        RedisTemplate<Object, Object> redisTemplate) {
    return new ParentChildIndexer(vectorStore, bm25Index, spotKnowledgeMapper,
            redisTemplate, new SpotKnowledgeEnricher(), new SpotKnowledgeSplitter());
}

@Bean
@ConditionalOnProperty(prefix = "rag.reranker", name = "enabled", havingValue = "true")
public QwenRerankClient qwenRerankClient(RagConfig ragConfig) {
    return new QwenRerankClient(ragConfig.getReranker());
}
```

`retrievalAugmentationAdvisor()` 参数类型从 `SpotDocumentRetriever` 改为 `HybridDocumentRetriever`。

`chatClient()` 中 `defaultTools(spotTools)` 不变。

### 4.3 `service/impl/SpotKnowledgeServiceImpl.java` — 索引流程增强

注入 `ParentChildIndexer` + `SpotKnowledgeMapper` + `RagConfig`：

```java
@Resource
private ParentChildIndexer parentChildIndexer;

@Resource
private SpotKnowledgeMapper spotKnowledgeMapper;
```

**`initializeSpotKnowledge(Long spotId)` 修改**：
```java
// 1. 查询 Spot
Spot spot = spotMapper.selectById(spotId);
String typeName = getTypeName(spot.getTypeId());

// 2. 委托 ParentChildIndexer 做全量索引（父+子→Qdrant, 子→BM25, 父→MySQL+Redis）
IndexingResult result = parentChildIndexer.indexSpot(spot, typeName);

// 3. 保留 Redis 元数据缓存（兼容现有其他读取路径）
redisTemplate.opsForValue().set(SPOT_KNOWLEDGE_PREFIX + spotId, result.getParentText());
Map<String, String> metadataStr = spotToMetadata(spot);
redisTemplate.opsForHash().putAll(SPOT_METADATA_PREFIX + spotId, metadataStr);
```

**`deleteSpotKnowledge(Long spotId)` 修改**：
```java
// 委托 ParentChildIndexer 清理（Qdrant + BM25 + MySQL + Redis）
parentChildIndexer.deleteSpot(spotId);
redisTemplate.delete(SPOT_KNOWLEDGE_PREFIX + spotId);
redisTemplate.delete(SPOT_METADATA_PREFIX + spotId);
```

### 4.4 `service/impl/SpotQAServiceImpl.java` — 最小修改

字段类型从 `SpotDocumentRetriever` 改为 `HybridDocumentRetriever`，其余逻辑不变（API兼容）。

### 4.5 `application-ai.yaml` — 新增配置段

```yaml
rag:
  # ===== 现有配置不变 =====
  top-k: 5
  similarity-threshold: 0.4
  max-context-spots: 10
  allow-empty-context: true
  system-prompt: |
    你是一个景点推荐助手...

  # ===== 新增配置 =====
  bm25:
    k1: 1.2
    b: 0.75
    top-k: 20

  hybrid:
    retrieval-candidates: 20
    default-vector-weight: 0.6
    normalization: "minmax"

  reranker:
    enabled: false              # 部署qwen3-rerank后设为true
    endpoint: ""
    model: "qwen3-rerank"
    top-k: 10
    score-threshold: 0.1
    timeout-ms: 5000
```

### 4.6 `tourmind-core/src/main/resources/db/hmdp.sql` — 追加建表语句

在文件末尾追加 `tb_spot_knowledge` 的建表语句（详见3.3节）。

---

## 五、数据流详解

### 5.1 知识库初始化

```
POST /ai/spot/knowledge/init/{spotId}
  → SpotKnowledgeServiceImpl.initializeSpotKnowledge(spotId)
    → SpotMapper.selectById(spotId)  → Spot 实体
    → SpotKnowledgeEnricher.enrich(spot, typeName)  → 结构化全文

    → ParentChildIndexer.indexSpot():
        ├─ 父Document: text=全文, metadata={spotId, spotName, docType:"parent"}
        ├─ SpotKnowledgeSplitter.split(父Document) → List<Document> 子文档
        ├─ vectorStore.add([父, 子1, 子2, ...])  → Qdrant
        ├─ 子文档逐一: bm25Index.addDocument("spotId:chunkIndex", chunkText, metadata)
        ├─ spotKnowledgeMapper.insert(SpotKnowledge{spotId, knowledgeText=全文})
        └─ redisTemplate.set("spot:knowledge:parent:{spotId}", 全文)

    → redisTemplate.set("spot:knowledge:{spotId}", 全文)  // 兼容旧key
    → redisTemplate.hPutAll("spot:metadata:{spotId}", metadata)
```

### 5.2 检索问答

```
POST /ai/spot/question {userId, question, userX, userY}
  → SpotQAServiceImpl.answerSpotQuestion()
    → conversationService.getOrCreateConversation()
    → spotDocumentRetriever.setUserCoordinates(x, y)

    → chatClient.prompt().user(question).call()
      → Advisor链:
        → MessageChatMemoryAdvisor: 注入对话历史
        → RetrievalAugmentationAdvisor:
          → CompressionQueryTransformer: 指代消解 ("第二个"→具体景点)
          → RewriteQueryTransformer: 口语→关键词
          → HybridDocumentRetriever.retrieve():
              ┌─ ① Qdrant向量检索 ──────────────────────┐
              │ similaritySearch(query, topK=20,           │
              │   filter="docType=='child'")               │
              │ → 按spotId去重保留最高分 → vectorResults   │
              └──────────────────────────────────────────┘
              ┌─ ② BM25关键词检索 ───────────────────────┐
              │ bm25Index.search(query, topK=20)           │
              │ → bm25Results                             │
              └──────────────────────────────────────────┘
              ┌─ ③ 动态权重 + 分数融合 ──────────────────┐
              │ w = QueryAnalyzer.determineVectorWeight()  │
              │ normVec = ScoreNormalizer.minMax(vector)    │
              │ normBM25 = ScoreNormalizer.minMax(bm25)     │
              │ fused = ScoreNormalizer.fuse(normVec,       │
              │          normBM25, w, retrievalCandidates)  │
              └──────────────────────────────────────────┘
              ┌─ ④ [reranker.enabled] 两阶段精排 ────────┐
              │ QwenRerankClient.rerank(                   │
              │   originalQuery, docTexts, topK)            │
              │ → 按relevance_score重排 + 阈值过滤         │
              └──────────────────────────────────────────┘
              ┌─ ⑤ 后处理 ──────────────────────────────┐
              │ 提取spotId → SpotMapper.selectBatchIds()   │
              │ → 距离排序 → ThreadLocal缓存               │
              │ → ParentChildIndexer.loadParentText(id)     │
              │   (Redis→MySQL→重建)                       │
              │ → 构建Document列表返回                     │
              └──────────────────────────────────────────┘
          → ContextualQueryAugmenter: 注入上下文
        → SimpleLoggerAdvisor: 日志
        → DeepSeek LLM + SpotTools: 生成回答

    → spotDocumentRetriever.getLastRetrievedSpots()
    → 构建SpotDTO → Result.ok({answer, sessionId, recommendedSpots})
    → finally: spotDocumentRetriever.clearContext()
```

### 5.3 父文档加载三级路径

```
ParentChildIndexer.loadParentText(spotId):
  ① Redis GET "spot:knowledge:parent:{spotId}"
     → 命中: 返回全文
     → 未命中: 进入②
  ② MySQL: spotKnowledgeMapper.selectById(spotId)
     → 命中: redisTemplate.set("spot:knowledge:parent:{spotId}", text) → 返回全文
     → 未命中: 进入③
  ③ 重建: SpotMapper.selectById(spotId)
     → SpotKnowledgeEnricher.enrich(spot, typeName)
     → spotKnowledgeMapper.insert(SpotKnowledge{...})
     → redisTemplate.set("spot:knowledge:parent:{spotId}", text)
     → 返回全文
```

---

## 六、MySQL新表

```sql
DROP TABLE IF EXISTS `tb_spot_knowledge`;
CREATE TABLE `tb_spot_knowledge` (
  `spot_id` bigint(20) UNSIGNED NOT NULL COMMENT '景点id',
  `knowledge_text` text NOT NULL COMMENT '富化后的完整知识文本（父文档）',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`spot_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
```

配套实体 `com.hmdp.entity.SpotKnowledge`（`@TableName("tb_spot_knowledge")`）和Mapper `com.hmdp.mapper.SpotKnowledgeMapper`（`BaseMapper<SpotKnowledge>`），放在 `tourmind-common` 模块。

---

## 七、实施顺序

### 阶段1：基础设施
1. 创建 `tb_spot_knowledge` 表 + `SpotKnowledge` 实体 + `SpotKnowledgeMapper`
2. 创建 `Bm25InvertedIndex` + `Bm25InvertedIndexTest`
3. 创建 `ScoreNormalizer` + `ScoreNormalizerTest`
4. 创建 `QueryAnalyzer` + `QueryAnalyzerTest`
5. 创建 `QwenRerankClient` + `QwenRerankClientTest`

### 阶段2：核心管道
6. 创建 `ParentChildIndexer` + `ParentChildIndexerTest`
7. 创建 `HybridDocumentRetriever` + `HybridDocumentRetrieverTest`

### 阶段3：集成
8. 修改 `RagConfig.java`（嵌套配置类）
9. 修改 `AIConfig.java`（Bean替换+新增）
10. 修改 `SpotKnowledgeServiceImpl.java`（委托ParentChildIndexer）
11. 修改 `SpotQAServiceImpl.java`（字段类型变更）
12. 修改 `application-ai.yaml`（新配置段）
13. 追加 SQL 到 `hmdp.sql`

### 阶段4：验证
```bash
# 编译
mvn clean compile -f pom.xml

# 单元测试
mvn test -pl tourmind-ai -am

# 集成测试（需MySQL/Redis/Qdrant/Ollama可用）
mvn test -pl tourmind-ai -am -Dgroups=integration

# 启动应用
mvn spring-boot:run -f tourmind-core/pom.xml

# 重建知识库（触发Parent-Child索引+BM25构建+MySQL持久化）
curl -X POST http://localhost:8082/ai/spot/knowledge/init-all

# 混合检索问答验证
curl -X POST http://localhost:8082/ai/spot/question \
  -H "Content-Type: application/json" \
  -d '{"userId":1,"question":"西湖有哪些好玩的自然景点","userX":120.15,"userY":30.28}'
```
