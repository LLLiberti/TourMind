# Agent 模式 vs RAG 管线 — Knowledge Tool 调用流程对比

## 1. 非 Agent 模式（固定 RAG 管线）

```
用户输入: "雷峰塔门票多少钱"
    │
    v
QueryRouter.classify()
    │
    ├─ CHITCHAT? ──→ 直接 LLM 回答（跳过 RAG）
    │
    └─ KNOWLEDGE → 进入固定管线
         │
         v
    ┌────────────────────────────────────────────────────────────┐
    │              RagAdvisor (Java硬编码，5步顺序执行)            │
    │                                                            │
    │  Step 1 ─ CompressionQueryTransformer                      │
    │          LLM 消解代词 ("第二个景点" → "雷峰塔")              │
    │                                                            │
    │  Step 2 ─ RewriteQueryTransformer                          │
    │          LLM 改写关键词 ("雷峰塔门票多少钱" → "雷峰塔 门票")   │
    │                                                            │
    │  Step 3 ─ HybridDocumentRetriever.retrieve("雷峰塔 门票")   │
    │          ┌─────────────────────────────────────┐           │
    │          │  Qdrant 向量检索 (topK=20)           │           │
    │          │  ES BM25 关键词检索 (topK=20)        │           │
    │          │  RRF 融合 (topK=20)                  │           │
    │          │  可选 Rerank (qwen3-rerank)          │           │
    │          │  DB 批量查询 Spot                    │           │
    │          │  ParentDoc 父文档加载                │           │
    │          │  距离排序                            │           │
    │          │  写入 ThreadLocal                    │           │
    │          └─────────────────────────────────────┘           │
    │                                                            │
    │  Step 4 ─ RetrievalEvaluator                               │
    │          计算置信度: CONFIDENT / AMBIGUOUS / INSUFFICIENT   │
    │          写入 ThreadLocal                                   │
    │                                                            │
    │  Step 5 ─ ContextualQueryAugmenter                          │
    │          将检索文档 + 系统提示词 拼入 prompt                  │
    │          LLM 看到的: [系统提示] [检索文档] [用户问题]         │
    └────────────────────────────────────────────────────────────┘
         │
         v
    ChatModel.call(prompt)
         │
         ├─ LLM 可能调用 getSpotPrice(42)   ← Function Calling（可选）
         └─ LLM 生成最终回答
              │
              v
    SpotQAServiceImpl:
      ├─ 从 ThreadLocal 读取置信度
      ├─ INSUFFICIENT? → 回答前追加免责声明 ⚠️
      ├─ 从 ThreadLocal 读取 Spot 列表
      └─ 构建 SpotDTO 响应

============================================================

## 2. Agent 模式（ReACT + Planner）

```
用户输入: "雷峰塔门票多少钱"
    │
    v
QueryRouter.classify()
    │
    ├─ CHITCHAT? ──→ 直接 LLM 回答（跳过一切）
    │
    └─ KNOWLEDGE → 进入 Agent
         │
         v
    ┌──────────────────────────────────────────────┐
    │  Planner.decompose("雷峰塔门票多少钱")         │
    │                                              │
    │  LLM 判断: 单一意图 → isComplex=false         │
    │  跳过分解，直接走 ReACT 循环                   │
    └──────────────────────────────────────────────┘
         │
         v
    ┌─────────────────────────────────────────────────────────────┐
    │               ReACT 循环 (LLM 自主决策每一步)                  │
    │                                                              │
    │  ┌─ ITER 1 ──────────────────────────────────────────────┐  │
    │  │                                                        │  │
    │  │ DeepSeek 推理:                                          │  │
    │  │   "用户问门票价格，需要先找到雷峰塔的信息"                 │  │
    │  │                                                        │  │
    │  │ → 输出 tool_calls:                                      │  │
    │  │   searchKnowledgeBase(query="雷峰塔")                   │  │
    │  │                                                        │  │
    │  │ Java 层手动执行:                                         │  │
    │  │   SearchKnowledgeBaseTool.execute("雷峰塔")              │  │
    │  │     ├─ RewriteQueryTransformer                          │  │
    │  │     └─ HybridDocumentRetriever.retrieve()               │  │
    │  │         ├─ Qdrant + ES + RRF + ParentDoc               │  │
    │  │         └─ 写入 ThreadLocal                             │  │
    │  │                                                        │  │
    │  │ → 追加 Observation 到消息列表:                            │  │
    │  │   [检索结果] 1篇文档 | CONFIDENT | spotId=42            │  │
    │  │   雷峰塔 | 文化古迹 | 评分4.3 | 西湖区                    │  │
    │  │   [建议] 结果可信，可直接使用。如需价格请调用对应工具      │  │
    │  └────────────────────────────────────────────────────────┘  │
    │                          │                                    │
    │                          v                                    │
    │  ┌─ ITER 2 ──────────────────────────────────────────────┐  │
    │  │                                                        │  │
    │  │ DeepSeek 推理:                                          │  │
    │  │   "找到了雷峰塔(spotId=42)，但无价格信息                  │  │
    │  │    需要调用 getSpotPrice"                                │  │
    │  │                                                        │  │
    │  │ → 输出 tool_calls:                                      │  │
    │  │   getSpotPrice(spotId=42)                               │  │
    │  │                                                        │  │
    │  │ Java 层手动执行:                                         │  │
    │  │   SpotToolService.getSpotPrice(42)                      │  │
    │  │     → "景点【雷峰塔】当前门票价格为 40 元"               │  │
    │  │                                                        │  │
    │  │ → 追加 Observation:                                     │  │
    │  │   雷峰塔当前门票价格为 40 元                              │  │
    │  └────────────────────────────────────────────────────────┘  │
    │                          │                                    │
    │                          v                                    │
    │  ┌─ ITER 3 ──────────────────────────────────────────────┐  │
    │  │                                                        │  │
    │  │ DeepSeek 推理:                                          │  │
    │  │   "有景点信息和价格，信息充足，生成回答"                  │  │
    │  │                                                        │  │
    │  │ → 输出 纯文本:                                           │  │
    │  │   "根据查询，雷峰塔当前门票价格为40元/人。                │  │
    │  │    雷峰塔位于西湖区，是文化古迹，评分4.3/5.0..."         │  │
    │  │                                                        │  │
    │  │ 循环结束 ← hasToolCalls() = false                       │  │
    │  └────────────────────────────────────────────────────────┘  │
    └─────────────────────────────────────────────────────────────┘
         │
         v
    SpotQAServiceImpl:
      ├─ 从 ThreadLocal 读取置信度
      ├─ INSUFFICIENT? → 追加免责声明
      ├─ 从 ThreadLocal 读取 Spot 列表
      └─ 构建响应 { answer, recommendedSpots, agentTrace }


============================================================

## 3. 如果检索结果差（INSUFFICIENT）

### 非 Agent 模式：
```
检索 → INSUFFICIENT → 追加免责声明 → 回答（不重试）
                       "⚠️ 以下信息基于一般知识..."
```

### Agent 模式：
```
searchKnowledgeBase("不存在的景点")
    │
    v
Observation: [检索结果] 0篇 | INSUFFICIENT | [建议] 换关键词重搜
    │
    v
DeepSeek 自主决策:
    "没找到，换个方式搜一下"
    │
    v
searchKnowledgeBase("abc 景点 杭州")  ← 自动重试！
    │
    v
Observation: 还是 INSUFFICIENT
    │
    v
DeepSeek: "两次都未找到，如实告知用户"
    │
    v
回答: "抱歉，未找到相关信息。建议检查景点名称..."
```

============================================================

## 4. 如果是复杂问题（触发 Planner）

```
用户: "西湖和雷峰塔哪个更好？门票各是多少？"

Planner.decompose() → isComplex=true
    │
    ├─ s1: "西湖的特色介绍和评分"  (dependsOn: [])
    ├─ s2: "雷峰塔的特色介绍和评分" (dependsOn: [])
    ├─ s3: "西湖门票价格"          (dependsOn: [s1])
    └─ s4: "雷峰塔门票价格"        (dependsOn: [s2])

executeSubTasks():
    │
    ├─ Level 0 (并行) ──────────────────┐
    │   ├─ s1: thinkAndAct("西湖特色")   │  每个子任务运行
    │   │    searchKnowledgeBase →       │  独立的 ReACT 循环
    │   │    观察 → 回答                  │  (max 3 轮)
    │   │                                │
    │   └─ s2: thinkAndAct("雷峰塔特色") │
    │        searchKnowledgeBase →       │
    │        观察 → 回答                  │
    └────────────────────────────────────┘
    │
    ├─ Level 1 (并行，等 Level 0) ───────┐
    │   ├─ s3: thinkAndAct(              │
    │   │   前置: 西湖特色...             │
    │   │   西湖门票价格)                 │
    │   │    searchKnowledgeBase →       │
    │   │    getSpotPrice(1) → 回答      │
    │   │                                │
    │   └─ s4: thinkAndAct(              │
    │       前置: 雷峰塔特色...           │
    │       雷峰塔门票价格)               │
    │        searchKnowledgeBase →       │
    │        getSpotPrice(2) → 回答      │
    └────────────────────────────────────┘
    │
    v
Planner.synthesize(4 个子结果)
    → "为您对比：西湖免费开放，评分4.5...
       雷峰塔门票40元，评分4.3...
       综合推荐：自然风光选西湖，人文历史选雷峰塔"


============================================================

## 5. 核心区别总结

┌─────────────────────┬──────────────────────┬──────────────────────┐
│         维度         │    非 Agent (RAG)     │   Agent 模式          │
├─────────────────────┼──────────────────────┼──────────────────────┤
│  检索触发时机        │ 每次请求强制执行       │ LLM 自主判断是否需要   │
│  检索调用方式        │ Java 硬编码管线        │ LLM 通过 tool_call    │
│                      │ (Advisor.before())    │ 调用 searchKnowledge  │
│                      │                       │ Base 工具             │
├─────────────────────┼──────────────────────┼──────────────────────┤
│  检索次数            │ 固定 1 次             │ 0~N 次，Agent 决定    │
├─────────────────────┼──────────────────────┼──────────────────────┤
│  Query 改写          │ RewriteQueryTransformer│ Agent 自己构造       │
│                      │ 单独的 LLM 调用        │ 关键词参数            │
├─────────────────────┼──────────────────────┼──────────────────────┤
│  检索结果处理        │ 拼入 prompt 模板       │ 以 Observation 形式   │
│                      │ (ContextualAugmenter)  │ 追加到消息列表        │
├─────────────────────┼──────────────────────┼──────────────────────┤
│  置信度反馈          │ Java 层 CRAG 评分     │ 嵌入 Observation 文本  │
│                      │ 仅追加免责声明         │ Agent 据此决策重搜    │
├─────────────────────┼──────────────────────┼──────────────────────┤
│  检索失败处理        │ 追加免责声明           │ Agent 自动换关键词    │
│                      │ 从不重试               │ 重新检索              │
├─────────────────────┼──────────────────────┼──────────────────────┤
│  检索+工具调用       │ 时序隔离               │ 交替进行              │
│                      │ 检索先完成              │ 搜→观→定价→观→答     │
│                      │ 再 LLM+FunctionCall    │                      │
├─────────────────────┼──────────────────────┼──────────────────────┤
│  复杂问题            │ 单 query 单次检索       │ Planner 分解→并行→综合│
├─────────────────────┼──────────────────────┼──────────────────────┤
│  迭代可观察性        │ 无（Advisor 黑盒）      │ AgentTrace 每步记录   │
│  调试                │                       │ (tool_call + observe) │
└─────────────────────┴──────────────────────┴──────────────────────┘

关键差异一句话：

    非 Agent: Java 硬编码"检索→注入→生成"，LLM 被动接收检索结果
    Agent:    LLM 主动调用 searchKnowledgeBase 工具，自己决定
             搜不搜、搜几次、搜得不够要不要换个姿势再搜
