# Phase 2: Planner Layer — 复杂问题分解与并行执行

## Context

Phase 1 实现了 ReACT Agent 循环，Agent 可以自主决策调用工具。但存在问题：Agent 的推理是**顺序的**——一次只能做一个 Thought→Action。当用户问复合问题时（"西湖和雷峰塔哪个更好？价格各是多少？附近有什么好吃的？"），Agent 需要 5-6 轮串行迭代才能完成。

Objective：在 ReActAgentLoop 前加一个 Planner 层，将复杂 query 分解为可并行的子问题，并行执行后合成最终回答。简单 query 跳过 Planner，零额外开销。

## 核心设计

```
thinkAndActWithPlan(question)
  │
  ├─ Planner.decompose(question)
  │    ├─ 简单 query？ → 直接执行 thinkAndAct()（零开销）
  │    └─ 复杂 query？ → 拆分为 subTasks[]
  │
  ├─ Execute subTasks（按依赖分层并行）
  │    ├─ Level 0: 无依赖的子问题 → CompletableFuture.allOf()
  │    ├─ Level 1: 依赖 Level 0 结果的子问题
  │    └─ 每个子问题执行简化 ReACT 循环（max 3 iters）
  │
  └─ Planner.synthesize(originalQuestion, allSubResults)
       └─ LLM 综合所有子结果 → 最终回答
```

## 新增文件

### 1. `agent/AgentPlan.java` (~30行)

```java
package com.hmdp.agent;

import lombok.Builder;
import lombok.Data;
import java.util.List;

@Data
public class AgentPlan {
    private boolean isComplex;           // false → 直接执行原 thinkAndAct
    private List<SubTask> subTasks;      // 子任务列表

    @Data @Builder
    public static class SubTask {
        private String id;               // s1, s2, ...
        private String question;         // 独立的子问题
        private List<String> dependsOn;  // 依赖的子任务 ID（空 = 可并行）
    }
}
```

### 2. `agent/PlannerAgent.java` (~100行)

```java
@Service
public class PlannerAgent {

    private final ChatClient plannerClient;
    // constructor: ChatClient.create(chatModel), 纯 LLM 调用，无 advisors

    /**
     * 分解用户问题为子任务。
     * 简单 query 返回 isComplex=false。
     */
    public AgentPlan decompose(String question, String conversationId) {
        // 构建 decompose prompt
        // LLM 输出 JSON: {"isComplex":true, "subTasks":[...]}
        // 解析 JSON → AgentPlan
    }

    /**
     * 综合所有子任务结果 → 最终回答。
     */
    public String synthesize(String originalQuestion, 
                             List<AgentPlan.SubTask> subTasks,
                             List<String> subResults) {
        // 构建 synthesize prompt
        // LLM 综合所有子回答 → 一个连贯的最终回答
    }
}
```

**Decompose Prompt 核心内容：**

```
判断用户问题是否需要拆分为多个独立的子问题。
如果需要拆分，输出子问题列表（每个子问题独立可回答）。

规则：
- 简单问题（单一意图）→ isComplex: false
- 复合问题（多个意图）→ isComplex: true, 拆分子问题
- 每个子问题必须自包含（包含必要的上下文）
- 标记依赖关系（如子问题B需要子问题A的结果）

示例输入："西湖和雷峰塔的特色分别是什么？门票各多少？"
输出：
{"isComplex":true, "subTasks":[
  {"id":"s1","question":"西湖的特色介绍和评分","dependsOn":[]},
  {"id":"s2","question":"雷峰塔的特色介绍和评分","dependsOn":[]},
  {"id":"s3","question":"西湖门票价格","dependsOn":["s1"]},
  {"id":"s4","question":"雷峰塔门票价格","dependsOn":["s2"]}
]}
```

### 3. 修改 `ReActAgentLoop.java` — 新增 `thinkAndActWithPlan()` (+60行)

在现有 `thinkAndAct()` 基础上新增 Planner 增强版：

```java
public AgentResult thinkAndActWithPlan(String question, String conversationId,
                                        Double userX, Double userY) {
    // 1. 尝试分解
    AgentPlan plan = planner.decompose(question, conversationId);
    
    if (!plan.isComplex()) {
        // 简单 query → 走原 ReACT 循环（零额外 LLM 调用）
        return thinkAndAct(question, conversationId, userX, userY);
    }
    
    // 2. 按依赖分层执行子任务
    Map<String, String> subResults = executeSubTasks(plan, conversationId, userX, userY);
    
    // 3. 综合
    String finalAnswer = planner.synthesize(question, 
        plan.getSubTasks(), 
        plan.getSubTasks().stream().map(t -> subResults.get(t.getId())).toList());
    
    AgentTrace trace = new AgentTrace();
    trace.setTotalIterations(plan.getSubTasks().size());
    return new AgentResult(finalAnswer, trace);
}

private Map<String, String> executeSubTasks(AgentPlan plan, 
        String conversationId, Double userX, Double userY) {
    Map<String, String> results = new ConcurrentHashMap<>();
    
    // 按依赖层级执行
    Set<String> completed = new HashSet<>();
    List<AgentPlan.SubTask> remaining = new ArrayList<>(plan.getSubTasks());
    
    while (!remaining.isEmpty()) {
        // 找出当前可执行（依赖已满足）的子任务
        List<AgentPlan.SubTask> ready = remaining.stream()
            .filter(t -> t.getDependsOn().stream().allMatch(completed::contains))
            .toList();
        
        if (ready.isEmpty()) break; // 循环依赖保护
        
        // 并行执行当前层
        CompletableFuture.allOf(ready.stream()
            .map(t -> CompletableFuture.supplyAsync(() -> {
                // 为每个子任务注入前序结果作为上下文
                String context = buildContext(t, results, plan);
                String taskQuestion = context.isEmpty() ? t.getQuestion() 
                    : context + "\n" + t.getQuestion();
                // 简化 ReACT（max 3 轮）
                AgentResult r = thinkAndAct(taskQuestion, conversationId, userX, userY);
                results.put(t.getId(), r.answer());
                return r.answer();
            }))
            .toArray(CompletableFuture[]::new)
        ).join();
        
        ready.forEach(t -> completed.add(t.getId()));
        remaining.removeAll(ready);
    }
    
    return results;
}

private String buildContext(SubTask task, Map<String,String> results, AgentPlan plan) {
    if (task.getDependsOn().isEmpty()) return "";
    StringBuilder ctx = new StringBuilder("前置信息：\n");
    for (String depId : task.getDependsOn()) {
        String depResult = results.get(depId);
        if (depResult != null) {
            ctx.append(depResult).append("\n");
        }
    }
    return ctx.toString();
}
```

新注入：`@Resource private PlannerAgent planner;`

### 4. 修改 `config/RagConfig.java` — AgentConfig 扩展 (+15行)

在 `AgentConfig` 类中加 Planner 子配置：

```java
@Data
public static class PlannerConfig {
    /** 是否启用 Planner */
    private boolean enabled = true;
    /** 每个子任务最大 ReACT 迭代次数 */
    private int maxSubIterations = 3;
    /** 子任务执行超时（毫秒） */
    private long subTimeoutMs = 15_000;
    /** 最大子任务数 */
    private int maxSubTasks = 5;
}

// 在 AgentConfig 中：
private PlannerConfig planner = new PlannerConfig();
```

YAML 路径：`rag.agent.planner.*`

### 5. 修改 `SpotQAServiceImpl.java` — 调用 Planner 版 Agent (+1行改动)

```java
// 原来: reActAgentLoop.thinkAndAct(question, conversationId, userX, userY);
// 改为:
AgentResult agentResult = reActAgentLoop.thinkAndActWithPlan(
        question, conversationId, userX, userY);
```

## 数据流示例

```
POST /ai/spot/question/agent { question: "西湖和雷峰塔哪个更好？门票各是多少？" }

thinkAndActWithPlan("西湖和雷峰塔哪个更好？门票各是多少？...")
  │
  ├─ Planner.decompose()  → isComplex=true, subTasks=[s1,s2,s3,s4]
  │
  ├─ executeSubTasks()
  │    │
  │    ├─ Level 0 (并行):
  │    │   ├─ s1: thinkAndAct("西湖的特色介绍和评分") → "西湖是自然风景区，评分4.5..."
  │    │   └─ s2: thinkAndAct("雷峰塔的特色介绍和评分") → "雷峰塔是文化古迹，评分4.3..."
  │    │
  │    └─ Level 1 (并行，依赖s1,s2):
  │        ├─ s3: thinkAndAct("前置: 西湖... \n 西湖门票价格") → "西湖免费开放"
  │        └─ s4: thinkAndAct("前置: 雷峰塔... \n 雷峰塔门票价格") → "雷峰塔40元"
  │
  └─ Planner.synthesize() → "为您对比分析：
       西湖（自然风景区，评分4.5）免费开放，适合...
       雷峰塔（文化古迹，评分4.3）门票40元，以...
       综合来看，如果喜欢自然风光推荐西湖，如果..."
```

## 技术债评估

| 债务项 | 严重度 | 说明 | 缓解措施 |
|--------|--------|------|---------|
| 两次 LLM 调用 | 中 | decompose() + synthesize() 增加 2 次额外调用 | 简单 query 跳过，仅复杂 query 触发 |
| JSON 解析脆弱性 | 低 | Planner 的 LLM 输出可能不合法 JSON | 异常时 fallback 到顺序执行 |
| 并行线程安全 | 低 | CompletableFuture 并发写入 ChatMemory | 使用 ConcurrentHashMap + 线程安全 ChatMemory |
| SpotQAServiceImpl 改动 | 极低 | 仅改一行：调用 thinkAndAct → thinkAndActWithPlan | 逻辑上兼容 |

## 回归风险评估

- **简单 query**：isComplex=false → 直接走原 thinkAndAct → **零行为变化**
- **现有 `/question/agent` 端点**：仅改变内部调用，请求/响应格式不变
- **ChatMemory**：InMemoryChatMemory 使用 ConcurrentLinkedDeque，并发安全
- **HybridDocumentRetriever ThreadLocal**：子任务在不同线程执行时各自拥有独立的 ThreadLocal

## 验证方案

### 单元测试
1. `PlannerAgentTest`：Mock ChatModel，测试 decompose 简单/复杂 query，JSON 解析边界
2. `ReActAgentLoopTest` 扩展：测试 thinkAndActWithPlan 对简单 query 透传，对复杂 query 分解执行

### 集成测试
3. 简单 query "西湖门票多少钱" → 验证走原 ReACT 路径，无 Planner 开销
4. 复合 query "西湖和雷峰塔对比" → 验证分解+并行执行+合成

### 手动验证
5. 调用 `/ai/spot/question/agent` 分别传入简单和复合 query，对比 agentTrace 中的步骤数

## 实现顺序

1. RagConfig.AgentConfig — 加 PlannerConfig
2. application-ai.yaml — 加 rag.agent.planner 配置
3. AgentPlan.java — 数据类
4. PlannerAgent.java — 分解+合成
5. ReActAgentLoop.java — 加 thinkAndActWithPlan()
6. SpotQAServiceImpl.java — 改一行调用
7. 测试类
