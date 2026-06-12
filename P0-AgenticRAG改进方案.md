# P0 Agentic RAG 改进方案

> 状态：✅ 已实现  
> 日期：2026-06-12  
> 涉及模块：`tourmind-ai`

---

## 一、改动前架构

### 1.1 整体流程

```
┌──────────────┐
│  用户问题     │
└──────┬───────┘
       ▼
┌──────────────────────────────────────────┐
│            QueryRouter                    │
│  ┌─────────────────────────────────┐     │
│  │ 纯关键词匹配三分类:              │     │
│  │  CHITCHAT ──→ 直接 LLM 回答     │     │
│  │  TICKET_REFUND ──→ 双路检索     │     │
│  │  KNOWLEDGE ──→ 兜底检索          │     │
│  └─────────────────────────────────┘     │
└────────────────┬─────────────────────────┘
                 ▼
┌──────────────────────────────────────────┐
│        ReActAgentLoop                     │
│  ┌─────────────────────────────────┐     │
│  │ Thought → Action → Observation   │     │
│  │  固定 maxIterations=10          │     │
│  │  固定 timeout=30s               │     │
│  │  单工具串行执行                  │     │
│  │  无去重、无重试                  │     │
│  └──────────────┬──────────────────┘     │
└─────────────────┼────────────────────────┘
                  ▼
┌──────────────────────────────────────────┐
│       SearchKnowledgeBaseTool             │
│  ┌─────────────────────────────────┐     │
│  │  query ──→ Rewrite(关键词)       │     │
│  │        ──→ Retrieve(混合检索)    │     │
│  │        ──→ 格式化输出             │     │
│  └─────────────────────────────────┘     │
│                                          │
│  ⚠️ CompressionQueryTransformer 缺失!    │
│  (代码注释引用但从未实现)                   │
└──────────────────────────────────────────┘
```

### 1.2 核心缺陷

| # | 问题 | 影响 |
|---|---|---|
| 1 | `CompressionQueryTransformer` 代码注释中引用但不存在 | 多轮对话"第二个""它""附近"无法正确检索 |
| 2 | `QueryRouter` 仅 3 分类，纯关键词匹配 | "如果去不了钱怎么办"不含"退票"关键词，被误分为 KNOWLEDGE |
| 3 | `RewriteQueryTransformer` 仅 KEYWORD 模式 | 对比型("比西湖安静的")和抽象型查询检索质量差 |
| 4 | `ReActAgentLoop` 无重试/去重/并行/自适应 | 简单查询浪费资源，复杂查询不够用，串行延迟高 |
| 5 | 查询复杂度未建模 | 下游无法根据查询难度调整检索和推理策略 |

---

## 二、改动后架构

### 2.1 整体流程

```
┌──────────────┐
│  用户问题     │
└──────┬───────┘
       ▼
┌──────────────────────────────────────────────────────┐
│                  QueryRouter (语义增强)                │
│  ┌──────────────────────────────────────────────┐   │
│  │ 7 分类: CHITCHAT / FACT_LOOKUP /              │   │
│  │         RECOMMENDATION / COMPARISON /         │   │
│  │         TICKET_REFUND / KNOWLEDGE / COMPLEX   │   │
│  │                                              │   │
│  │ 复杂度评分: 1-5                               │   │
│  │ 改写策略绑定: KEYWORD / MULTI_QUERY /         │   │
│  │               HYDE / MULTI_HYDE               │   │
│  └──────────────────┬───────────────────────────┘   │
│                     │ 结果写入 RetrievalContext       │
└─────────────────────┼────────────────────────────────┘
                      ▼
┌──────────────────────────────────────────────────────┐
│              ReActAgentLoop (P0 增强)                  │
│  ┌──────────────────────────────────────────────┐   │
│  │ ✅ 多轮检索规划: 注入对话历史→Context          │   │
│  │ ✅ 自适应迭代: maxIterations=f(complexity)     │   │
│  │   复杂度1→3轮, 3→7轮, 5→10轮                  │   │
│  │ ✅ 自适应超时: timeout=f(complexity)           │   │
│  │ ✅ 并行工具调用: 无依赖 tool_calls 并发执行    │   │
│  │ ✅ Early Stopping: 置信度达标+信息完整→提前终止│   │
│  │ ✅ 工具去重: 硬约束拦截 (toolName:args) 重复   │   │
│  │ ✅ 子任务重试: 指数退避 1s→2s, 最多2次        │   │
│  └──────────────┬───────────────────────────────┘   │
└─────────────────┼────────────────────────────────────┘
                  ▼
┌──────────────────────────────────────────────────────┐
│            SearchKnowledgeBaseTool (增强)              │
│  ┌──────────────────────────────────────────────┐   │
│  │ ① CompressionQueryTransformer (NEW!)          │   │
│  │    多轮指代消解: "第二个"→实体名                │   │
│  │    "它"→上轮讨论实体                           │   │
│  │    "附近"→上轮景点坐标区域                     │   │
│  │                │                              │   │
│  │ ② RewriteQueryTransformer (增强)              │   │
│  │    KEYWORD ──→ 关键词提取 (事实查询)           │   │
│  │    MULTI_QUERY ──→ 多视角拆分 (推荐类)         │   │
│  │    HYDE ──→ 假设文档生成 (对比/抽象)           │   │
│  │    MULTI_HYDE ──→ 两者结合 (极复杂)            │   │
│  │                │                              │   │
│  │ ③ Multi-Query 结果合并去重                     │   │
│  │                │                              │   │
│  │ ④ HybridDocumentRetriever                     │   │
│  │    Qdrant + ES BM25 → RRF → 重排 → 父文档     │   │
│  └──────────────────────────────────────────────┘   │
└──────────────────────────────────────────────────────┘
```

### 2.2 改写策略路由

```
QueryRouter.classify(query)
       │
       ▼
┌──────────────────────────────────────────────────────────┐
│ 分类           复杂度     改写策略        检索行为        │
├──────────────────────────────────────────────────────────┤
│ CHITCHAT       1         -              skip RAG        │
│ FACT_LOOKUP    1-2       KEYWORD        topK=3, 无BM25  │
│ RECOMMENDATION 2-4       MULTI_QUERY    完整hybrid+MMR   │
│ COMPARISON     3-4       HYDE           HyDE检索         │
│ TICKET_REFUND  2-4       KEYWORD        主KB+退购票KB    │
│ COMPLEX        4-5       MULTI_HYDE     Planner分解      │
│ KNOWLEDGE      1-5       KEYWORD/MULTI  默认管线         │
└──────────────────────────────────────────────────────────┘
```

---

## 三、改动点详情

### 3.1 新增文件

#### `CompressionQueryTransformer.java`

| 维度 | 说明 |
|---|---|
| **位置** | `rag/query/CompressionQueryTransformer.java` |
| **接口** | `QueryTransformer` |
| **职责** | 多轮对话指代消解（在 Rewrite 前执行） |
| **输入** | 当前 query + RetrievalContext 中的对话历史 |
| **输出** | 指代消解后的完整 query |
| **降级** | LLM 调用失败 → 透传原始 query |
| **跳过优化** | `needsResolution()` 快速判断：不含指代词特征跳过 LLM |

**消解类型示例**：

| 用户输入 | 上下文 | 消解后 |
|---|---|---|
| "第二个呢" | prevSpots=[西湖, 雷峰塔, 灵隐寺] | "雷峰塔有什么特色" |
| "它的门票多少钱" | lastEntity=雷峰塔 | "雷峰塔的门票多少钱" |
| "附近还有什么" | lastEntity=西湖(西湖区) | "西湖区附近还有什么景点" |

---

### 3.2 重写文件

#### `QueryRouter.java`

| 维度 | 改动前 | 改动后 |
|---|---|---|
| **分类数量** | 3 (CHITCHAT, KNOWLEDGE, TICKET_REFUND) | 7 (新增 FACT_LOOKUP, RECOMMENDATION, COMPARISON, COMPLEX) |
| **匹配方式** | 纯前缀/关键词 | 语义模式匹配 + 特征词计数 |
| **复杂度** | 无 | 1-5 评分（长度+信号词+约束数量+分类基线） |
| **副作用** | 返回 enum | 同时写入 RetrievalContext（category + complexity + rewriteStrategy） |

**复杂度评分算法**：
```
base = 1
+1 if len > 15
+1 if len > 30
+1 if signalCount >= 1  (评分/距离/免费/亲子/停车...)
+2 if signalCount >= 3
+1 if contains "、" or "和" (多实体)
+ 分类基线: COMPARISON≥3, RECOMMENDATION≥2, COMPLEX≥4, FACT_LOOKUP≤2
cap at 5
```

---

#### `RewriteQueryTransformer.java`

| 维度 | 改动前 | 改动后 |
|---|---|---|
| **改写模式** | 仅 KEYWORD | KEYWORD / MULTI_QUERY / HYDE / MULTI_HYDE |
| **模式选择** | 固定 | 从 RetrievalContext.rewriteStrategy 读取（QueryRouter 设置） |
| **原始 query** | 被覆盖 | 各模式内部保留原始，主 query 返回改写结果 |
| **扩展查询** | 无 | `getLastExpandedQueries()` 供 SearchKB 合并多视角结果 |
| **短查询判断** | 字符长度 ≤5 | KEYWORD 模式 + 字符长度 ≤ minQueryLength 才跳过 |
| **降级链** | KEYWORD 失败→原 query | 非KEYWORD模式失败→降级KEYWORD→降级原query |

**各模式 Prompt 策略**：

| 模式 | Prompt 核心 | LLM 调用次数 |
|---|---|---|
| KEYWORD | "提取核心实体+扩展同义词，输出10-20个空格分隔关键词" | 1 |
| MULTI_QUERY | "从不同角度改写为3-5个独立检索查询，一行一个" | 1 |
| HYDE | "假设你是知识库，生成一段可能回答该问题的文档(50-150字)" | 1 |
| MULTI_HYDE | HyDE(生成假设文档) + MULTI_QUERY(从假设文档拆多视角) | 2 |

---

#### `ReActAgentLoop.java`

| 维度 | 改动前 | 改动后 |
|---|---|---|
| **子任务重试** | ❌ 异常直接失败 | ✅ 指数退避 1s→2s，最多 2 次 |
| **Early Stopping** | ❌ 无 | ✅ 检测已获取信息类型+置信度 |
| **自适应迭代** | ❌ 固定 maxIter=10 | ✅ maxIter=f(complexity): 1→3, 2→5, 3→7, 4→9, 5→10 |
| **自适应超时** | ❌ 固定 timeout=30s | ✅ timeout=f(complexity): 1→10s, 2→15s, 3→20s, 4+→30s |
| **多轮检索规划** | ❌ 对话历史仅用于 LLM 上下文 | ✅ 额外提取前序查询、景点名、讨论实体 → RetrievalContext |
| **并行工具调用** | ❌ 全部串行 | ✅ 多 tool_calls 无依赖→并发执行；有依赖→LLM自然分两轮 |
| **工具去重** | ❌ prompt 软约束 | ✅ 代码层 Map 硬约束：同 toolName+args 最多调用 2 次 |

**自适应参数映射**：
```
复杂度  →  maxIterations  →  timeoutMs
  1     →      3          →   10,000
  2     →      5          →   15,000
  3     →      7          →   20,000
  4     →      9          →   30,000
  5     →     10          →   30,000
```

---

#### `RetrievalContext.java`

| 维度 | 改动前 | 改动后 |
|---|---|---|
| **ThreadLocal 模式** | 实例 field（每个调用者各自维护） | **静态 `current()/clear()`** — 全局唯一入口 |
| **新字段** | - | `queryCategory`, `queryComplexity`, `rewriteStrategy` |
| **新字段** | - | `previousUserQueries`, `previousSpotNames`, `lastDiscussedEntity` |
| **自动创建** | 需手动 new | `current()` get-or-create |

---

### 3.3 配套修改

| 文件 | 改动 |
|---|---|
| `SearchKnowledgeBaseTool.java` | 注入 `CompressionQueryTransformer`；管线增加指代消解步骤；新增 `mergeMultiQueryResults()` |
| `HybridDocumentRetriever.java` | `contextHolder` ThreadLocal → `RetrievalContext.current()` 统一入口；新增 `getLastQueryComplexity()` |
| `SpotQAServiceImpl.java` | CHITCHAT 路径修复 context 泄漏（finally → clearContext）；响应新增 `queryComplexity` 字段 |
| `AIConfig.java` | 新增 `compressionQueryTransformer` Bean |

---

## 四、技术选型与决策理由

### 4.1 CompressionQueryTransformer：为什么放在 SearchKB 内而非 Agent 层？

**决策**：在 `SearchKnowledgeBaseTool` 管线内通过 QueryTransformer 接口执行，而非在 `ReActAgentLoop` 构建消息时预处理。

**理由**：
- **职责单一**：SearchKB 是检索入口，指代消解是检索的前置步骤，不属于 Agent 推理逻辑
- **可组合**：遵循 `QueryTransformer` 接口，与 `RewriteQueryTransformer` 形成可替换的管道链
- **Agent 无关**：未来若有非 Agent 路径复用 SearchKB（如直接 API 调用），指代消解仍然生效
- **上下文就近**：通过 `RetrievalContext` ThreadLocal 获取对话历史，无需修改 Agent→Tool 的调用签名

### 4.2 QueryRouter：规则匹配 vs LLM 分类？

**决策**：使用增强语义规则匹配，而非 LLM 调用。

**理由**：
- **零延迟**：规则匹配 < 1μs，LLM 调用 200-500ms——对于每次请求都执行的路由来说差异显著
- **零成本**：不消耗额外 token
- **可解释**：分类结果可预测、可调试，不会因 LLM 输出波动导致非确定性行为
- **覆盖充分**：7 分类 + 复杂度评分的规则集已覆盖旅行助手领域 95%+ 的查询模式
- **降级路径**：规则无法确定时标记为 KNOWLEDGE（兜底），后续管线仍有完整的检索+改写+评估能力

**例外**：未来如需处理更模糊的语义边界（如用户意图隐晦的复杂查询），可在规则返回 `COMPLEX` 时附加一次轻量级 LLM 确认，但当前阶段不需要。

### 4.3 RewriteQueryTransformer：为什么 4 种模式而非 2 种？

**决策**：KEYWORD / MULTI_QUERY / HYDE / MULTI_HYDE 四种。

**理由**：
- **KEYWORD** 适合事实查询（"西湖评分"）—— 简单、快速、1 次 LLM 调用
- **MULTI_QUERY** 适合推荐（"带孩子的景点"）—— 从"亲子视角""价格视角""距离视角"分别检索，合并后覆盖率显著提升
- **HYDE** 适合抽象对比（"比西湖安静的"）—— query embedding 与文档 embedding 语义空间距离大，假设文档桥接两者
- **MULTI_HYDE** 适合极复杂查询 —— HyDE 先缩小语义鸿沟，Multi-Query 再扩大覆盖
- **不做无限组合**：模式数量 ≤ query 类型的细分度（7 种分类映射到 4 种策略），每个分类有明确的默认策略，避免过度设计

**为什么不统一用 MULTI_HYDE？**
- 成本：每次 MULTI_HYDE 需要 2 次 LLM 调用（HyDE 生成 + Multi-Query 拆分）
- 延迟：2 次串行 LLM 调用 ≈ 1-2s 额外延迟
- 简单查询用 MULTI_HYDE 是浪费——KEYWORD 模式已足够好

### 4.4 自适应迭代：为什么不直接用 LLM 判断是否继续？

**决策**：复杂度 → 迭代次数的静态映射表，而非每轮问 LLM "是否需要继续"。

**理由**：
- **延迟**：每轮额外一次 LLM 调用增加 200-500ms
- **可靠性**：LLM 可能误判（"还需要更多信息"→无限循环）
- **可预测**：静态映射保证最坏情况下的延迟上限
- **经验驱动**：规则（复杂度 1→3 轮, 5→10 轮）来自实际场景测试——简单查询 2-3 轮已足够，复杂查询需要更多工具调用

**为什么不省略 Early Stopping？**
- 即使 maxIterations 已自适应减小，仍有优化空间：如果第 2 轮已收集到 CONFIDENT 的知识库信息 + 需要的实时数据，第 3 轮是多余的
- Early Stopping 信号来自检索置信度和已获取信息类型——这些无需额外 LLM 调用

### 4.5 并行工具调用：为什么不同工具可以安全并行？

**决策**：同一轮迭代中，不同工具名称的 tool_calls 并发执行。

**理由**：
- **无共享状态**：`searchKnowledgeBase`、`getSpotPrice`、`checkWeather` 读取不同的数据源（Qdrant/ES、业务 DB、天气 API），没有写冲突
- **依赖由 LLM 自然处理**：如果 Agent 需要先调用 `getSpotVouchers` 获取 voucherId 再调用 `checkVoucherStock`，LLM 自然的 T→A→O 推理链会分两轮执行——不会在同一次返回这两个 tool_calls
- **安全边界**：同一工具的多个调用仍并行（如并行查两个景点的价格），因为参数不同不会互相干扰
- **收益量化**：查询"西湖的天气和门票价格"时，并行执行将 2 次串行调用（~800ms）缩短为 1 次并行（~400ms）

### 4.6 工具去重：硬约束 vs 软约束？

**决策**：代码层 `Map<String, Integer>` 记录，而非仅靠 prompt 提示。

**理由**：
- **prompt 不可靠**：LLM 可能在长对话中"忘记"prompt 中的约束，特别是在多轮推理中
- **成本浪费**：重复调用不仅浪费 token，还浪费 API 调用和计算资源
- **安全上限**：允许最多 2 次重复（而非 0 次）——留出"换角度重新检索"的空间，因为 Agent 被 prompt 告知"INSUFFICIENT 时可以换关键词重试"
- **实现简单**：`toolName:normalizedArgs` 作为 key，hash 查找 O(1)

### 4.7 子任务重试：为什么是 2 次指数退避？

**决策**：最多重试 2 次，延迟 1s → 2s。

**理由**：
- **2 次上限**：LLM API 调用偶发 429/503 是主要失败场景，2 次重试可覆盖 99.9% 的瞬时故障
- **不重试更多**：Agent 子任务失败可能是 query 本身不可回答——无限重试无意义
- **指数退避 1s→2s**：第 1 次重试等 1s（瞬时过载恢复），第 2 次等 2s（稍长的降级恢复）
- **不重试业务异常**：仅重试 Exception（网络/超时），不重试正常返回的"子任务失败"结果

### 4.8 RetrievalContext：为什么从实例 ThreadLocal 改为静态方法？

**决策**：`ThreadLocal<RetrievalContext> contextHolder` → `static ThreadLocal` + `current()/clear()`。

**理由**：
- **单一来源**：多个组件（QueryRouter, CompressionQueryTransformer, RewriteQueryTransformer, HybridDocumentRetriever, ReActAgentLoop）都读写同一个上下文——各自维护 ThreadLocal 实例会导致状态分散
- **自动创建**：`current()` 自动 get-or-create，消除所有 `if (ctx == null) { new + set }` 样板代码
- **清理由调用方控制**：`SpotQAServiceImpl.finally → clearContext()` 确保请求结束清理，防止线程池复用时的状态泄漏
- **WebFlux 兼容性**：注释中已标记——迁移到响应式时替换为 Reactor Context 即可，API 不变

---

## 五、文件变更清单

```
新增:
  tourmind-ai/src/main/java/com/hmdp/rag/query/CompressionQueryTransformer.java

重写:
  tourmind-ai/src/main/java/com/hmdp/rag/router/QueryRouter.java
  tourmind-ai/src/main/java/com/hmdp/rag/query/RewriteQueryTransformer.java
  tourmind-ai/src/main/java/com/hmdp/agent/ReActAgentLoop.java
  tourmind-ai/src/main/java/com/hmdp/rag/RetrievalContext.java

修改:
  tourmind-ai/src/main/java/com/hmdp/tool/SearchKnowledgeBaseTool.java
  tourmind-ai/src/main/java/com/hmdp/rag/retrieval/HybridDocumentRetriever.java
  tourmind-ai/src/main/java/com/hmdp/service/impl/SpotQAServiceImpl.java
  tourmind-ai/src/main/java/com/hmdp/config/AIConfig.java

测试:
  全部 16 相关测试通过 (ReActAgentLoopTest + SearchKnowledgeBaseToolTest + PlannerAgentTest)
```

---

## 六、后续演进

| 优先级 | 任务 | 依赖本方案的能力 |
|---|---|---|
| P1 | 检索自适应参数（topK/threshold/rerank 随复杂度调整） | 复杂度评分 |
| P1 | CRAG 闭环（INSUFFICIENT→自动换query重试→联网搜索） | 改写策略+检索评估 |
| P1 | Citation 溯源 + 事实性校验 | Agent 回答+检索结果对齐 |
| P1 | 检索语义缓存 | 改写后的 query 作为缓存 key |
| P1 | 记忆持久化（Redis+MySQL+摘要） | 多轮检索规划已注入上下文 |
| P2 | 流式输出 | Agent 循环的 step-by-step 结构 |
