# CLAUDE.md

## Build & Test

```bash
mvn clean compile -f pom.xml              # Build all
mvn test -f pom.xml                       # Unit tests (no infra needed)
mvn test -pl tourmind-ai -am              # AI module unit tests
mvn test -pl tourmind-ai -am -Dgroups=integration  # Integration tests (needs infra)
mvn clean package -DskipTests -f pom.xml  # Package
mvn spring-boot:run -f tourmind-core/pom.xml  # Run (port 8082, profile: ai)
```

JDK 17, Maven 3.9+. Infra: MySQL `3306/dp`, Redis `6379`, RocketMQ `9876`, Qdrant `6333` (REST) / `6334` (gRPC), Ollama `11434` (`bge-m3`).

## Module Architecture

```
tourmind (parent)
├── tourmind-common — entities, DTOs, MyBatis-Plus Mappers
├── tourmind-ai     — RAG Q&A, sentiment, conversation memory
└── tourmind-core   — entry point, seckill, blogs, users, spots
```

Dependency: `common` ← `ai` ← `core`. Main class: `tourmind-core/…/TourMindApplication`.

### tourmind-core highlights
- **Seckill**: `VoucherOrderController` → Redisson lock → RocketMQ transactional msg → Lua atomic stock deduction → consumer writes DB (optimistic lock `stock > 0`).
- **Cache**: Bloom filter → Redis → Redisson lock (double-check) → DB → write back with random TTL. Null values cached 2 min.
- **Auth**: `RefreshTokenInterceptor` extracts token → Redis Hash → `UserContext` ThreadLocal. `LoginInterceptor` enforces.
- **Geo**: Redis `GEOSEARCH` within 5km.
- **Config**: `RedisConfig` (GenericJackson2JsonRedisSerializer), `BloomFilterConfig`, `TreadPoolConfig`.

### tourmind-ai — RAG (Spring AI 1.0.7)
Advisor chain: `MessageChatMemoryAdvisor` → `RetrievalAugmentationAdvisor`(`SpotDocumentRetriever` + `ContextualQueryAugmenter`) → `SimpleLoggerAdvisor`.

Key files:
- `AIConfig` — Advisor beans + ChatClient assembly. VectorStore auto-configured from `application-ai.yaml` (Qdrant).
- `SpotDocumentRetriever` (`rag/retrieval/`) — Custom `DocumentRetriever`: vector search → DB lookup → distance sort → ThreadLocal cache → enriched Documents.
- `RetrievalContext` (`rag/`) — ThreadLocal POJO (`userX`, `userY`, `retrievedSpots`).
- `SpotDTO` (`dto/`) — Response DTO, `SpotDTO.from(Spot)` factory.
- `SpotQAServiceImpl` — Two modes: (1) RAG retrieval + advisor chain, (2) known spot + manual context. `finally` must call `spotDocumentRetriever.clearContext()`.
- `ChatMemoryConfig.InMemoryChatMemory` — `ConcurrentHashMap` storage, sliding window, no persistence.
- `ConversationServiceImpl` — 5-min cleanup, syncs with `ChatMemory`.

Config keys: `rag.*`, `sentiment.*`, `conversation.*`. DeepSeek key: env `DEEPSEEK_API_KEY`.

## Key Conventions
- **@Transactional self-call**: Use `AopContext.currentProxy()`; requires `@EnableAspectJAutoProxy(exposeProxy = true)`.
- **ThreadLocal cleanup**: `spotDocumentRetriever.clearContext()` in `finally`; `UserContext.removeUser()` in `afterCompletion`.
- **Lock safety**: `lock.isHeldByCurrentThread()` check; try-finally.
- **Redis keys**: `<domain>:<subdomain>:` pattern per `RedisConstant`.
- **Packages**: `config`, `controller`, `service/impl`, `mapper`, `entity`, `dto`, `rag`.

## Test Conventions
- **Unit**: JUnit 5 + Mockito (`@ExtendWith(MockitoExtension.class)`). `mvn test`. Classes: `SpotDTOTest`, `SpotDocumentRetrieverTest`, `SpotQAServiceImplTest`.
- **Integration**: `@Tag("integration")`, excluded by surefire. Run with `-Dgroups=integration`. Classes: `SpotQARagTest`, `RagRetrievalEvaluationTest`, `RagGenerationEvaluationTest`, `SpotKnowledgeInitTest`, `ConversationManagementTest`.
- New Service → must add unit tests.

## 硬性约束
1. 全局兼容 — 新代码不能与已有功能冲突
2. 禁止硬编码/临时逻辑/破坏契约
3. 不能在完整项目上下文外单独工作
4. 新增 Service 必须写单元测试,测试必须使用业务环境而不是mock环境

## 输出要求
生成代码前：
1. 分析现有实现 
2. 给出方案 
3. 方案同意后再写代码 
4. 优先复用已有代码
