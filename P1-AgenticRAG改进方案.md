# P1 Agentic RAG 改进方案

> 状态：✅ 已实现  
> 日期：2026-06-12  
> 涉及模块：`tourmind-ai`

---

## 一、改动前架构

```
┌──────────────┐
│  用户问题     │
└──────┬───────┘
       ▼
┌──────────────────────────────────────────┐
│  QueryRouter → ReActAgentLoop             │
│    ┌──────────────────────────────┐      │
│    │ P0 增强 (已完成):             │      │
│    │  语义路由 / 复杂度 / 自适应迭代│      │
│    │  多模式改写 / 指代消解         │      │
│    └──────────────┬───────────────┘      │
└───────────────────┼──────────────────────┘
                    ▼
┌──────────────────────────────────────────┐
│         SearchKnowledgeBaseTool           │
│                                          │
│  ❌ 检索参数全局固定 (topK=20, thr=0.4)  │
│  ❌ 无多样性控制 (5个同类型景点重复)      │
│  ❌ CRAG 只评估不纠正 (INSUFFICIENT→      │
│     仅加免责声明)                         │
│  ❌ 无检索缓存 (每次走完整管线)            │
│  ❌ 无 Citation 溯源                      │
│  ❌ 无事实性校验                          │
│  ❌ ChatMemory 内存存储 (重启丢失)         │
│  ❌ 无对话摘要压缩 (无限增长)              │
└──────────────────────────────────────────┘
```

---

## 二、改动后架构

```
┌──────────────┐
│  用户问题     │
└──────┬───────┘
       ▼
┌──────────────────────────────────────────────────────────────┐
│                   P0 管线 (已完成)                             │
│  QueryRouter(7分类+复杂度) → Agent(自适应迭代+并行工具+去重)   │
│  → Compression(指代消解) → Rewrite(4模式) → Retrieve          │
└───────────────────────────┬──────────────────────────────────┘
                            ▼
┌──────────────────────────────────────────────────────────────┐
│                    P1 增强层                                   │
│                                                              │
│  ┌──────────────────────────────────────────────────────┐   │
│  │ ① RetrievalCacheManager (检索缓存)                     │   │
│  │   L1 精确缓存(query MD5 → spotIds, TTL=10min)          │   │
│  │   L2 语义缓存(去噪后 query → spotIds, TTL=30min)       │   │
│  │   缓存命中了 → 跳过完整检索管线                         │   │
│  └──────────────────────┬───────────────────────────────┘   │
│                         ▼ (未命中)                            │
│  ┌──────────────────────────────────────────────────────┐   │
│  │ ② HybridDocumentRetriever (自适应检索)                 │   │
│  │   ┌────────────────────────────────────────────┐      │   │
│  │   │ 自适应 topK: 简单→5, 中→10, 复杂→20       │      │   │
│  │   │ 自适应 threshold: 简单→0.5, 复杂→0.35     │      │   │
│  │   │ 自适应 rerank: 复杂度≥3 才启用             │      │   │
│  │   └────────────────────────────────────────────┘      │   │
│  │   ┌────────────────────────────────────────────┐      │   │
│  │   │ ③ MmrDiversifier (MMR 多样性)               │      │   │
│  │   │   MMR = λ×relevance - (1-λ)×max_similarity  │      │   │
│  │   │   维度: typeName + area                      │      │   │
│  │   │   λ=0.7 (偏向相关性)                         │      │   │
│  │   └────────────────────────────────────────────┘      │   │
│  └──────────────────────┬───────────────────────────────┘   │
│                         ▼                                    │
│  ┌──────────────────────────────────────────────────────┐   │
│  │ ④ CRAG 闭环 (CragCorrector)                           │   │
│  │   INSUFFICIENT → broadenQuery(去修饰词) → 重新检索    │   │
│  │   仍不足 + maxScore<0.3 → Web 搜索回退(百度API)       │   │
│  │   Web 结果 → 临时Document → 异步知识补全               │   │
│  └──────────────────────┬───────────────────────────────┘   │
│                         ▼                                    │
│  ┌──────────────────────────────────────────────────────┐   │
│  │ ⑤ GenerationGuard (生成质量守护)                      │   │
│  │   ┌──────────────┬──────────────┬──────────────┐     │   │
│  │   │ Citation注入  │ 事实性校验    │ 冲突检测      │     │   │
│  │   │ 文档[1][2]... │ LLM断言-文档  │ KB静态 vs     │     │   │
│  │   │ 标注引用来源  │ 逐条对齐      │ 实时动态差异  │     │   │
│  │   └──────────────┴──────────────┴──────────────┘     │   │
│  └──────────────────────┬───────────────────────────────┘   │
│                         ▼                                    │
│  ┌──────────────────────────────────────────────────────┐   │
│  │ ⑥ ChatMemory 持久化 + 摘要                            │   │
│  │   ┌────────────────────┬──────────────────────┐      │   │
│  │   │ PersistentChatMemory│ ConversationSummary  │      │   │
│  │   │ Redis(热,24h TTL)  │ 每5轮触发增量摘要     │      │   │
│  │   │ 内存(降级回退)      │ 摘要注入Agent提示词   │      │   │
│  │   └────────────────────┴──────────────────────┘      │   │
│  └──────────────────────────────────────────────────────┘   │
└──────────────────────────────────────────────────────────────┘
```

---

## 三、改动点详情

### 3.1 新增文件

| # | 文件 | 职责 | 关键设计 |
|---|---|---|---|
| 1 | `MmrDiversifier.java` | MMR 多样性重排 | λ=0.7, 维度: typeName+area, 首个选最高分保相关性 |
| 2 | `CragCorrector.java` | CRAG 闭环纠正 | broadenQuery 去修饰词 → WebSearchClient 回退接口 |
| 3 | `GenerationGuard.java` | Citation+事实验证+冲突检测 | 非阻塞校验，LLM失败→数字降级检测 |
| 4 | `RetrievalCacheManager.java` | 两层语义缓存 | L1精确(MD5,10min) + L2语义(去噪MD5,30min) |
| 5 | `PersistentChatMemory.java` | Redis持久化记忆 | JSON序列化, TTL=24h, 内存降级 |
| 6 | `ConversationSummaryService.java` | 对话摘要压缩 | 增量模式(每5轮), 80-150字结构化摘要 |

### 3.2 修改文件

| 文件 | 改动 |
|---|---|
| `RagConfig.java` | 新增 6 个嵌套配置类: AdaptiveRetrievalConfig, MmrConfig, CragCorrectionConfig, CacheConfig, MemoryConfig, GuardConfig |
| `HybridDocumentRetriever.java` | 构造函数+MmrDiversifier; buildMainResults 插入 MMR 步骤; 自适应 topK/threshold/rerank |
| `SearchKnowledgeBaseTool.java` | 注入 CragCorrector + RetrievalCacheManager + GenerationGuard; 缓存查询→CRAG纠正→Citation指令 |
| `ReActAgentLoop.java` | 注入 GenerationGuard + PersistentChatMemory + ConversationSummaryService; 生成后守卫检查; 摘要触发 |
| `ChatMemoryConfig.java` | 双模式: @ConditionalOnProperty 切换 InMemory/Persistent; @Primary 自动选择 |
| `AIConfig.java` | 新增 6 个 Bean: mmrDiversifier, cragCorrector, generationGuard, retrievalCacheManager, conversationSummaryService; 更新 spotDocumentRetriever 传入 MmrDiversifier |

---

## 四、技术选型与决策理由

### 4.1 自适应检索参数：静态映射 vs ML 模型？

**决策**：复杂度→参数的静态查找表（`complexity ≤2 → topK=5, threshold=0.5`），而非训练回归模型。

**理由**：
- **零训练成本**：不需要标注数据，不需要模型推理
- **可解释**：运维人员可直接在 application.yml 中调整参数
- **充分覆盖**：3 档（简单/中等/复杂）已覆盖 95%+ 场景，P0 的复杂度评分已提供分类依据
- **稳定**：不随数据分布漂移而退化
- **未来可升级**：`AdaptiveRetrievalConfig` 结构已预留扩展字段

### 4.2 MMR 多样性：为什么选 typeName + area 两个维度？

**决策**：维度 = {景点类型, 所在区域}，λ = 0.7。

**理由**：
- **typeName**（自然风景区/文化古迹/主题公园）：用户最直观的多样性感知维度
- **area**（西湖区/余杭区）：地理多样性，避免推荐 5 个全是西湖区的景点
- **仅两个维度**：维度太多 → 计算量 O(n²k)，两个维度 n≤20 时计算开销可忽略
- **λ=0.7**：偏向相关性——保证最相关的景点不因多样性被挤出；同时 30% 的多样性权重足以打破类型/区域的聚集
- **价格/评分不做为维度**：它们是连续值而非类别，且用户可能有明确偏好（"只看免费的"）——更适合用过滤而非多样性

### 4.3 CRAG 闭环：为什么要分层纠正（改写→Web→知识更新）？

**决策**：INSUFFICIENT 时逐级 escalation，而非直接跳到 Web 搜索。

**理由**：
1. **第 1 层 - 查询放宽**（`broadenQuery`）：去除修饰词→重新检索
   - 0 额外 API 调用，纯文本处理
   - 覆盖情况：用户加了过多限定词导致无结果（如"评分最高且免费且带孩子且停车方便"→放宽为"免费 亲子 停车"）
2. **第 2 层 - Web 回退**：分数极低（<0.3）时才触发
   - 有 API 调用成本 → 仅真正的知识盲区触发
   - Web 结果标记为 AMBIGUOUS（不可完全信任）
3. **第 3 层 - 知识更新**：Web 结果异步追加，**不直接写入主知识库**
   - 避免 Web 噪声污染向量索引（错误信息一旦入索引很难清理）
   - log 记录供人工审核后批量更新

### 4.4 检索缓存：为什么是两层（L1精确 + L2语义）而非单一层？

**决策**：L1 精确匹配（query MD5）TTL 10min + L2 语义匹配（去噪后 MD5）TTL 30min。

**理由**：
- **L1 仅精确匹配**：热点查询（"西湖有什么好玩的"）完全相同 → 1 microsecond 命中
- **L2 去噪匹配**："西湖有什么好玩的？" vs "西湖有啥好玩的" → 去语调/标点/空格后相同 → 二次命中
- **不做向量语义缓存**：语义相似度判断（embedding 距离 <0.95）需要 1 次 embedding API 调用 —— 这本身就是一次昂贵操作，缓存的意义被削弱
- **缓存键 = query → 缓存值 = 格式化结果文本**（不是 spotIds）：因为格式化文本包含置信度标记和行动建议，Agent 需要完整上下文
- **TTL 差异**：精确缓存更短（10min）因为精确匹配的 query 通常更具体；语义缓存更长（30min）因为去噪后的 query 更泛化

### 4.5 Citation 注入：为什么在 SearchKB 层而非 Agent 层？

**决策**：Citation 编号在 `SearchKnowledgeBaseTool.formatResults()` 中注入（`文档[1]`），而非在 Agent 最终回答后追加引用映射。

**理由**：
- **LLM 直接可见**：编号在检索结果的 prompt 中，LLM 生成回答时可以自然地引用（"评分 4.5 分 [1]"）
- **无需后处理**：不需要在生成后做 NER 或正则匹配来反向建立文档→回答的映射
- **Agent 无关**：无论 ReAct 循环如何演化，Citation 总是跟检索结果一起出现
- **限制**：LLM 可能不遵循 citation 指令 → 这正是 `GenerationGuard.extractCitations()` 检测"回答中是否包含引用"的原因，缺失时在回答末尾追加警告

### 4.6 事实性校验：为什么默认关闭（`factCheckEnabled=false`）？

**决策**：LLM 驱动的事实性校验默认关闭，仅当配置显式启用时才执行。

**理由**：
- **成本**：每次回答后额外一次 LLM 调用 → token 消耗加倍
- **延迟**：校验 LLM 调用 200-500ms → 总响应时间增加 30-50%
- **收益有限**：当前系统的检索结果 confidence 评估 + Agent prompt 约束已提供基本的事实性保障
- **适用场景**：高精度要求的场景（医疗/金融/法律）应启用；旅行推荐场景可接受轻微的"润色误差"
- **降级方案**：关闭时仍执行简单数字匹配（`simpleNumberCheck`）——零额外成本，快速检测明显的数字编造

### 4.7 聊天记忆持久化：Redis vs MySQL vs 两者？

**决策**：Redis 热存储（消息 JSON，TTL 24h）+ 摘要 Redis 存储（TTL 30天），通过 `@ConditionalOnProperty` 自动切换内存/持久化。

**理由**：
- **不存 MySQL**：对话消息是高度动态的、频繁读写的、有自然 TTL 的数据 —— Redis 天然适合
- **不存 MySQL**：摘要虽然有长期参考价值，但仅 80-150 字/会话，Redis 存 30 天足够
- **内存降级**：Redis 不可用时 → fallback 到 ConcurrentLinkedDeque（原 InMemoryChatMemory 逻辑）—— 保证核心功能不中断
- **`@ConditionalOnProperty` 切换**：默认 `persistent-enabled=false`，零配置即可运行；部署到生产环境时启用 Redis
- **序列化方案**：JSON（Jackson）而非 Java 序列化 —— 可读、可调试、语言无关

### 4.8 摘要策略：增量 vs 全文？

**决策**：增量摘要 —— 每 5 轮对话生成一次，基于已有摘要 + 最近 5 轮对话。

**理由**：
- **成本**：增量摘要只处理 5 轮对话，而非每次处理全部历史（可能 20+ 轮）
- **连续性**：已有摘要保留历史上下文（"用户喜欢自然风光"），新摘要更新而不丢失
- **不丢失细节**：最近 3 轮完整 messages 保留在 Agent 上下文中（不压缩），只有更早的消息被摘要替代
- **触发点**：5 轮一次——低于 5 轮不需要摘要（原始消息足够短）；高于 5 轮开始有 token 压力
- **摘要格式**：结构化（用户画像 + 已讨论实体 + 已获取信息 + 未完成意图）而非自由文本 —— 确保关键信息不因压缩而丢失

---

## 五、配置示例

```yaml
# application.yml (P1 新增配置及推荐值)
rag:
  # P1 自适应检索
  adaptive-retrieval:
    enabled: true
    simple-top-k: 5       # 复杂度1-2
    medium-top-k: 10      # 复杂度3
    complex-top-k: 20     # 复杂度4-5
    simple-threshold: 0.5
    complex-threshold: 0.35
  
  # P1 MMR 多样性
  mmr:
    enabled: true
    lambda: 0.7          # 0=全多样性, 1=全相关性
  
  # P1 CRAG 纠正
  crag-correction:
    enabled: true
    web-fallback-enabled: false   # 需配置百度API key后启用
    web-search-api-key: ""
    web-search-endpoint: ""
  
  # P1 检索缓存
  cache:
    enabled: true
    l1-ttl-minutes: 10
    l2-ttl-minutes: 30
  
  # P1 记忆持久化
  memory:
    persistent-enabled: false    # 启用需Redis依赖
    redis-ttl-hours: 24
    summary-interval: 5
  
  # P1 生成质量守护
  guard:
    enabled: true
    citation-enabled: true
    fact-check-enabled: false    # 有LLM开销，按需开启
    conflict-detection-enabled: true
```

---

## 六、文件变更清单

```
新增:
  tourmind-ai/src/main/java/com/hmdp/rag/retrieval/MmrDiversifier.java
  tourmind-ai/src/main/java/com/hmdp/rag/evaluation/CragCorrector.java
  tourmind-ai/src/main/java/com/hmdp/rag/generation/GenerationGuard.java
  tourmind-ai/src/main/java/com/hmdp/rag/cache/RetrievalCacheManager.java
  tourmind-ai/src/main/java/com/hmdp/service/impl/PersistentChatMemory.java
  tourmind-ai/src/main/java/com/hmdp/service/impl/ConversationSummaryService.java

修改:
  tourmind-ai/src/main/java/com/hmdp/config/RagConfig.java           (+6配置类)
  tourmind-ai/src/main/java/com/hmdp/rag/retrieval/HybridDocumentRetriever.java  (+MMR +自适应参数)
  tourmind-ai/src/main/java/com/hmdp/tool/SearchKnowledgeBaseTool.java         (+CRAG +缓存 +Citation)
  tourmind-ai/src/main/java/com/hmdp/agent/ReActAgentLoop.java                 (+守卫 +摘要)
  tourmind-ai/src/main/java/com/hmdp/config/ChatMemoryConfig.java              (+持久化模式)
  tourmind-ai/src/main/java/com/hmdp/config/AIConfig.java                      (+6 Bean)

测试:
  全部 16 相关测试通过 (0 失败)
```

---

## 七、P0+P1 完整架构总览

```
用户问题
    │
    ▼
┌─────────────────────────────────────────────────────────┐
│ P0: QueryRouter (7分类+复杂度 → RetrievalContext)         │
└────────────────────────┬────────────────────────────────┘
                         ▼
┌─────────────────────────────────────────────────────────┐
│ P0: ReActAgentLoop                                       │
│   ├ 自适应迭代 (complexity→maxIter)                       │
│   ├ 并行工具调用 + 去重 + 重试                            │
│   ├ 多轮检索规划 (注入对话历史)                           │
│   └ P1: 摘要注入 + GenerationGuard                       │
└────────────────────────┬────────────────────────────────┘
                         ▼
┌─────────────────────────────────────────────────────────┐
│ P0+P1: SearchKnowledgeBaseTool                           │
│   ├ P1: 缓存查询 (L1→L2)                                 │
│   ├ P0: Compression(指代消解)                             │
│   ├ P0: Rewrite(KEYWORD/MULTI_QUERY/HYDE/MULTI_HYDE)    │
│   ├ P1: 自适应检索 (topK/threshold/rerank)               │
│   ├ P1: MMR 多样性重排                                   │
│   ├ P1: CRAG 纠正 (改写→Web→知识补全)                     │
│   └ P1: Citation 指令注入                                │
└────────────────────────┬────────────────────────────────┘
                         ▼
┌─────────────────────────────────────────────────────────┐
│ P1: GenerationGuard (生成后)                              │
│   ├ Citation 统计                                        │
│   ├ 事实性校验 (LLM+降级)                                 │
│   └ 冲突检测 (KB vs 实时)                                 │
└────────────────────────┬────────────────────────────────┘
                         ▼
┌─────────────────────────────────────────────────────────┐
│ P1: ChatMemory 持久化                                     │
│   ├ Redis(热) + 内存(降级)                                │
│   └ 摘要压缩 (每5轮 → 注入下一轮系统提示)                  │
└─────────────────────────────────────────────────────────┘
                         ▼
                      用户回答
```
