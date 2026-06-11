# 方案 B 精简 — 删除 RagAdvisor 改动详解

## 概述

删除 `AIConfig.java` 中的 RagAdvisor（硬编码 RAG 管线 ~117行），
将 `answerSpotQuestion()` 委托到 Agent 路径，消除两套管线并存的技术债。

改动 4 个文件，净删除 ~120 行，测试 105 个全部通过。

---

## 改动清单

### 1. AIConfig.java — 删除 RagAdvisor Bean

**文件**：`C:\programming\Programing\Idea\TourMind\tourmind-ai\src\main\java\com\hmdp\config\AIConfig.java`

**删除了什么**：整个 `ragAdvisor()` @Bean 方法（原 233-349 行，117 行）

**具体删掉的代码块**：

```java
// 删除了以下完整方法：
@Bean
public BaseAdvisor ragAdvisor(HybridDocumentRetriever spotDocumentRetriever,
                               @Qualifier("deepSeekChatModel") ChatModel chatModel,
                               ChatMemory chatMemory,
                               RagConfig ragConfig) {
    CompressionQueryTransformer compressor = ...
    RewriteQueryTransformer rewriter = ...
    ContextualQueryAugmenter augmenter = ...
    return new BaseAdvisor() {
        @Override
        public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
            // 硬编码 5 步管线：
            //   Step 1: 加载 ChatMemory 历史
            //   Step 2: CompressionQueryTransformer 消解代词
            //   Step 3: RewriteQueryTransformer 关键词改写
            //   Step 4: HybridDocumentRetriever 检索
            //   Step 5: ContextualQueryAugmenter 增强 prompt
            ...
        }
        public String getName() { return "RagAdvisor"; }
        public ChatClientResponse after(...) { ... }
        public int getOrder() { return 0; }
    };
}
```

**理由**：RagAdvisor 是 Modular RAG 的核心——Java 硬编码的 5 步固定管线（Compress→Rewrite→Retrieve→Augment）。Agent 路径（ReActAgentLoop + Planner）已经实现了 LLM 自主决策的检索流程，两个路径功能等价但 Agent 路径更灵活（支持自纠正重试、复杂分解、并行执行）。删除 RagAdvisor 消除了双路径维护负担。

> **注意**：这只是删除了 Java 代码层面的 RAG 编排逻辑。
> HybridDocumentRetriever、QueryRouter、RetrievalEvaluator 等 RAG 组件仍然存在，
> 被 Agent 路径的 SearchKnowledgeBaseTool 作为工具内部使用。

---

### 2. AIConfig.java — 删除 buildAugmenterPrompt()

**文件**：同上

**删除了什么**：`buildAugmenterPrompt()` 私有静态方法（原 351-366 行，16 行）

**理由**：此方法仅为 RagAdvisor 内部的 ContextualQueryAugmenter 构建 prompt 模板（`{context}` + `{query}` 占位符）。RagAdvisor 删除后无调用方。Agent 路径的系统提示词在 `RagConfig.AgentConfig.systemPrompt` 中配置，不需要此模板。

---

### 3. AIConfig.java — 新增 RewriteQueryTransformer Bean

**文件**：同上

**新增了什么**：

```java
@Bean
public RewriteQueryTransformer rewriteQueryTransformer(
        @Qualifier("deepSeekChatModel") ChatModel chatModel, RagConfig ragConfig) {
    return new RewriteQueryTransformer(chatModel, ragConfig.getRewrite().getMinQueryLength());
}
```

**理由**：RewriteQueryTransformer 原本是 RagAdvisor 内部的局部变量，不是 Spring Bean。但 Agent 路径的 `SearchKnowledgeBaseTool` 通过 `@Resource` 注入它。删除 RagAdvisor 后，必须显式声明为 Bean 否则 Spring 启动失败。

---

### 4. AIConfig.java — 简化 ChatClient

**文件**：同上

**改动前**：

```java
@Bean
public ChatClient chatClient(@Qualifier("deepSeekChatModel") ChatModel chatModel,
                              MessageChatMemoryAdvisor memoryAdvisor,
                              BaseAdvisor ragAdvisor,           // ← 删除
                              SpotTools spotTools,
                              WeatherTools weatherTools) {
    return ChatClient.builder(chatModel)
            .defaultAdvisors(memoryAdvisor, ragAdvisor, new SimpleLoggerAdvisor())
            //                              ^^^^^^^^^ 删除
            .defaultTools(spotTools, weatherTools)
            .build();
}
```

**改动后**：

```java
@Bean
public ChatClient chatClient(@Qualifier("deepSeekChatModel") ChatModel chatModel,
                              MessageChatMemoryAdvisor memoryAdvisor,
                              SpotTools spotTools,
                              WeatherTools weatherTools) {
    return ChatClient.builder(chatModel)
            .defaultAdvisors(memoryAdvisor, new SimpleLoggerAdvisor())
            .defaultTools(spotTools, weatherTools)
            .build();
}
```

**理由**：ChatClient 不再承担 RAG 检索职责（已迁移到 Agent 路径）。现在仅用于闲聊（`answerChitchat`）和指定景点问答（`answerQuestionAboutSpot`）——这些场景不需要强制检索。减少 Advisor 链长度降低每次调用的延迟。

---

### 5. AIConfig.java — 精简 imports

**文件**：同上

**删除了这些 import**：

| 删除的 import | 原因 |
|---|---|
| `org.springframework.ai.chat.client.ChatClientRequest` | 仅在 RagAdvisor 中使用 |
| `org.springframework.ai.chat.client.ChatClientResponse` | 仅在 RagAdvisor 中使用 |
| `org.springframework.ai.chat.client.advisor.api.AdvisorChain` | 仅在 RagAdvisor 中使用 |
| `org.springframework.ai.chat.client.advisor.api.BaseAdvisor` | 仅在 RagAdvisor 中使用 |
| `org.springframework.ai.chat.messages.Message` | 仅在 RagAdvisor 中使用 |
| `org.springframework.ai.chat.prompt.PromptTemplate` | 仅在 buildAugmenterPrompt 中使用 |
| `org.springframework.ai.document.Document` | 仅在 RagAdvisor 中使用 |
| `org.springframework.ai.rag.Query` | 仅在 RagAdvisor 中使用 |
| `org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter` | 仅在 RagAdvisor 中使用 |
| `org.springframework.ai.rag.preretrieval.query.transformation.CompressionQueryTransformer` | 仅在 RagAdvisor 中使用 |
| `org.springframework.ai.rag.retrieval.search.DocumentRetriever` | 仅在 RagAdvisor 中使用 |

**保留的关键 import**：

| 保留的 import | 原因 |
|---|---|
| `RewriteQueryTransformer` | 新增的 @Bean 声明需要 |
| `HybridDocumentRetriever` | spotDocumentRetriever Bean 仍在构建 |
| `RetrievalEvaluator` | spotDocumentRetriever Bean 依赖 |
| 其他 RAG 组件（EsBm25Retriever, RrfRankFuser 等） | 仍在 AIConfig 中声明为 Bean |

---

### 6. AIConfig.java — 更新类级 Javadoc

**文件**：同上

**改动**：将模块描述从 "模块化 RAG + Function Calling 混合架构" 更新为 "Agentic RAG 架构"。删除 RagAdvisor 相关文档段落，新增 Agent 路径说明。

---

### 7. SpotQAServiceImpl.java — answerSpotQuestion 委托到 Agent

**文件**：`C:\programming\Programing\Idea\TourMind\tourmind-ai\src\main\java\com\hmdp\service\impl\SpotQAServiceImpl.java`

**改动前**（73 行方法体，包含完整的 ChatClient+Advisor 调用逻辑）：

```java
@Override
public Result answerSpotQuestion(...) {
    // 1. 校验 question
    // 2. 获取会话
    // 3. QueryRouter 分流
    // 4. 设置坐标 → ChatClient.prompt().advisors(...).user(...).call()
    // 5. CRAG 评估
    // 6. 构建 SpotDTO
    // 7. finally clearContext
    return ...;
}
```

**改动后**（3 行）：

```java
@Override
public Result answerSpotQuestion(Long userId, String sessionId, String question,
                                  Double userX, Double userY, int limit) {
    return answerSpotQuestionAgent(userId, sessionId, question, userX, userY, limit);
}
```

**理由**：`answerSpotQuestion()` 和 `answerSpotQuestionAgent()` 功能完全等价——都是问答，区别仅在于检索管线（RagAdvisor vs ReACT Agent）。Agent 路径已验证为 DeepSeek function calling 稳定可靠、功能更强（支持重试、复杂分解、并行执行），因此统一到 Agent 路径。两个控制器端点（`/question` 和 `/question/agent`）行为完全一致。

---

### 8. SpotQAServiceImpl.java — 删除 buildPromptWithCoordinates()

**文件**：同上

**删除了什么**：

```java
private String buildPromptWithCoordinates(String question, Double userX, Double userY) {
    if (userX != null && userY != null) {
        return String.format("（当前用户位于经度 %.4f、纬度 %.4f 的位置，请优先推荐距离近的景点）%s",
                userX, userY, question);
    }
    return question;
}
```

**理由**：此方法仅为旧的 `answerSpotQuestion()` 路径使用——在调用 ChatClient 前将坐标提示注入 prompt 文本。Agent 路径通过 `retriever.setUserCoordinates()` 将坐标写入 ThreadLocal，HybridDocumentRetriever 在检索时自动进行距离排序，无需在 prompt 中显式提示。方法无其他调用方。

---

### 9. SpotQAServiceImpl.java — 清理 imports

**文件**：同上

**删除的 import**：`com.hmdp.rag.evaluation.RetrievalEvaluator`

**理由**：原代码在 `answerSpotQuestion()` 中使用了 `RetrievalEvaluator.INSUFFICIENT_HINT` 常量（提供免责声明文本）。新代码直接使用字符串 `"INSUFFICIENT"` 与 ThreadLocal 中的置信度值比较，不再引用 `RetrievalEvaluator` 类。

**删除的 import**：`org.springframework.ai.chat.client.ChatClient`

**理由**：嗯，实际上 `ChatClient` 仍被 `answerChitchat()` 和 `answerQuestionAboutSpot()` 使用，所以**保留**此 import。

---

### 10. SpotQAServiceImpl.java — 更新类级 Javadoc

**文件**：同上

**改动**：架构描述从 "Modular RAG（ChatClient + RagAdvisor）" 更新为 "Agentic RAG（ReActAgentLoop + Planner）"。`answerSpotQuestion` 的流程描述更新为委托到 Agent。

---

### 11. SpotQAServiceImplTest.java — 更新测试

**文件**：`C:\programming\Programing\Idea\TourMind\tourmind-ai\src\test\java\com\hmdp\SpotQAServiceImplTest.java`

**改动点**：

**a) AnswerSpotQuestionTests** — 重构 setUp：
- 删除 `initChatClientMock()` 调用（不再通过 ChatClient 调用 LLM）
- 新增 `initAgentMock()`（Mock `reActAgentLoop.thinkAndActWithPlan()` 返回预设回答）
- `shouldReturnAnswerWithSpots`：验证 Agent 返回的回答文本和 Spot 列表
- `shouldFailOnEmptyQuestion`：验证空问题 Agent 不被调用
- `shouldCleanupOnException`：改为 Mock Agent 抛出异常，验证 finally 仍清理 ThreadLocal

**b) CRAGFallbackTests** — 更新 setUp：
- 删除 `initChatClientMock()`
- 新增 `initAgentMock()`
- 测试逻辑不变（仍 Mock `spotDocumentRetriever.getLastRetrievalConfidence()` 返回 INSUFFICIENT / CONFIDENT）

**理由**：`answerSpotQuestion()` 改为委托到 Agent 后，ChatClient 不再参与该路径。测试必须 Mock 新的调用链（`reActAgentLoop.thinkAndActWithPlan()`）。

**c) 保留不变的测试类**：
- `AnswerChitchatTests`：闲聊路径仍使用 ChatClient，不变
- `AnswerQuestionAboutSpotTests`：指定景点路径仍使用 ChatClient，不变
- `ConversationManagementTests`：会话管理无关 RAG，不变

---

## 改动汇总

| # | 文件 | 变更类型 | 行数变化 | 理由 |
|---|------|---------|---------|------|
| 1 | AIConfig.java | 删除 ragAdvisor() Bean | -117 | Agent 路径已替代，消除双路径 |
| 2 | AIConfig.java | 删除 buildAugmenterPrompt() | -16 | 仅 RagAdvisor 使用，无调用方 |
| 3 | AIConfig.java | 新增 RewriteQueryTransformer Bean | +8 | Agent 路径需要此 Bean |
| 4 | AIConfig.java | 简化 ChatClient | -2 | 删除 ragAdvisor 参数 |
| 5 | AIConfig.java | 精简 imports | -11 | 删除仅 RagAdvisor 使用的类引用 |
| 6 | AIConfig.java | 更新 Javadoc | 修改 | 架构描述更新 |
| — | | **AIConfig.java 小计** | **-138 行** | |
| 7 | SpotQAServiceImpl.java | answerSpotQuestion 改为委托 | -70 | 统一到 Agent 路径 |
| 8 | SpotQAServiceImpl.java | 删除 buildPromptWithCoordinates() | -6 | 仅旧路径使用 |
| 9 | SpotQAServiceImpl.java | 清理 imports | -1 | 删除 RetrievalEvaluator 引用 |
| 10 | SpotQAServiceImpl.java | 更新 Javadoc | 修改 | 架构描述更新 |
| — | | **SpotQAServiceImpl.java 小计** | **-77 行** | |
| 11a | SpotQAServiceImplTest.java | 重构 AnswerSpotQuestionTests | 修改 | Mock 链从 ChatClient 改为 Agent |
| 11b | SpotQAServiceImplTest.java | 更新 CRAGFallbackTests | 修改 | 同上 |
| — | | **测试小计** | **净 -20 行** | |
| | | **总计** | **净 -235 行** | |

## 未修改的文件（零风险保障）

以下文件在方案 B 中**零改动**，确保现有功能完全不受影响：

| 文件 | 原因 |
|------|------|
| `HybridDocumentRetriever.java` | 被 Agent 路径的 SearchKnowledgeBaseTool 内部使用 |
| `RetrievalEvaluator.java` | 被 HybridDocumentRetriever 内部使用 |
| `QueryRouter.java` | 被 SpotQAServiceImpl 的闲聊分流复用 |
| `SpotTools.java` / `WeatherTools.java` | 被 ReActAgentLoop 的工具定义复用 |
| `RewriterQueryTransformer.java` | 现在作为 Bean 被 SearchKnowledgeBaseTool 注入 |
| `Controller/SpotQAController.java` | 两个端点的路由逻辑不变 |
| `ISpotQAService.java` | 接口签名不变 |
| `RagConfig.java` | 配置类未修改（Agent 路径使用其 AgentConfig 子配置） |

## 验证

```bash
mvn test -pl tourmind-ai -am
# 结果: BUILD SUCCESS, 105 tests, 0 failures
```
