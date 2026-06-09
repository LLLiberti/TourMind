# Spring AI 智能评论情感分析与商铺问答功能

## 一、功能概述

### 1.1 智能评论情感分析
对博客/评论进行情感判断（好评/中评/差评），自动统计商铺好评率，可用作筛选和排序依据。

### 1.2 智能商铺问答 (RAG)
用户输入问题，AI 结合商铺信息回答。如："附近有什么适合约会的餐厅？"

---

## 二、技术架构

### 2.1 版本要求
| 组件 | 版本 |
|-----|------|
| Java | 17+ |
| Spring Boot | 3.3.5 |
| Spring AI | 1.0.0 |
| MyBatis-Plus | 3.5.7 |
| Redis | 6.0+ |

### 2.2 项目依赖升级
项目已升级以下依赖以支持 Spring AI：
- Spring Boot: 2.3.12 → 3.3.5
- Java: 1.8 → 17
- MyBatis-Plus: 3.4.3 → 3.5.7 (使用 `mybatis-plus-spring-boot3-starter`)
- 新增 Spring AI 1.0.0 依赖
- `javax.*` 命名空间迁移至 `jakarta.*`

### 2.3 架构图
```
用户请求
    │
    ▼
┌─────────────────────────────────────────┐
│           Spring AI Controller           │
│  SentimentAnalysisController            │
│  ShopQAController                      │
└────────────────┬──────────────────────┘
                 │
    ┌────────────┴────────────┐
    ▼                         ▼
┌─────────────┐        ┌─────────────────┐
│ 情感分析服务 │        │ 商铺问答服务     │
│ Sentiment   │        │ ShopQA (RAG)    │
│ Analysis    │        │                 │
└──────┬──────┘        └────────┬────────┘
       │                        │
       ▼                        ▼
┌─────────────┐        ┌─────────────────┐
│  ChatClient │        │ VectorStore     │
│ (GPT-4o)   │        │ (Redis)         │
└─────────────┘        └────────┬────────┘
                                │
                                ▼
                       ┌─────────────────┐
                       │ EmbeddingModel  │
                       │ (text-embedding)│
                       └─────────────────┘
```

---

## 三、配置文件

### 3.1 环境变量配置
使用前需配置以下环境变量：

| 环境变量 | 说明 | 示例 |
|---------|------|------|
| `OPENAI_API_KEY` | OpenAI API Key | `sk-xxxxxx` |
| `OPENAI_BASE_URL` | API 地址（可选） | `https://api.openai.com` |
| `REDIS_PASSWORD` | Redis 密码 | `004147` |

### 3.2 application-ai.yaml 配置
```yaml
spring:
  ai:
    # OpenAI 配置
    openai:
      api-key: ${OPENAI_API_KEY:your-api-key-here}
      base-url: ${OPENAI_BASE_URL:https://api.openai.com}
      chat:
        options:
          model: gpt-4o-mini
          temperature: 0.7
    embedding:
      options:
        model: text-embedding-3-small
        dimensions: 1536

    # Redis 向量存储配置
    vectorstore:
      redis:
        client-type: jedis
        host: 127.0.0.1
        port: 6379
        password: ${REDIS_PASSWORD:004147}
        index: shop-knowledge-base
        dimension: 1536
        prefix: "shop:vector:"

# 情感分析配置
sentiment:
  batch-size: 100
  cache-ttl-hours: 1

# RAG 配置
rag:
  top-k: 5
  similarity-threshold: 0.6
  max-context-shops: 10
```

### 3.3 主配置文件 application.yaml
确保激活 ai profile：
```yaml
spring:
  profiles:
    active: ai
```

---

## 四、API 接口文档

### 4.1 情感分析接口

#### 4.1.1 分析单条评论情感
```
POST /ai/sentiment/analyze
Content-Type: application/json

{
    "content": "这家餐厅太好吃了，服务也超棒！"
}
```

**响应示例：**
```json
{
    "code": 200,
    "msg": "success",
    "data": {
        "sentiment": "POSITIVE"
    }
}
```

**情感标签说明：**
| 标签 | 含义 |
|-----|------|
| `POSITIVE` | 好评 |
| `NEUTRAL` | 中评 |
| `NEGATIVE` | 差评 |

#### 4.1.2 获取商铺好评率
```
GET /ai/sentiment/shop/{shopId}/rate
```

**响应示例：**
```json
{
    "code": 200,
    "msg": "success",
    "data": {
        "shopId": 1,
        "positiveRate": 0.85,
        "percentage": "85.0%"
    }
}
```

#### 4.1.3 批量分析商铺评论情感
```
POST /ai/sentiment/shop/{shopId}/analyze-batch
```

**响应示例：**
```json
{
    "code": 200,
    "msg": "success",
    "data": "分析完成"
}
```

---

### 4.2 智能问答接口

#### 4.2.1 智能商铺问答
```
POST /ai/shop/question
Content-Type: application/json

{
    "question": "附近有什么适合约会的餐厅？",
    "userX": 121.5033,
    "userY": 31.2374,
    "limit": 5
}
```

**参数说明：**
| 参数 | 类型 | 必填 | 说明 |
|-----|------|-----|------|
| question | String | 是 | 用户问题 |
| userX | Double | 否 | 用户经度 |
| userY | Double | 否 | 用户纬度 |
| limit | Integer | 否 | 返回数量，默认5 |

**响应示例：**
```json
{
    "code": 200,
    "msg": "success",
    "data": {
        "answer": "根据您的需求，我为您推荐以下适合约会的餐厅：...",
        "recommendedShops": [
            {
                "id": 1,
                "name": "浪漫餐厅",
                "area": "陆家嘴",
                "address": "东方明珠对面",
                "avgPrice": 300,
                "score": 5,
                "openHours": "10:00-22:00",
                "distance": 1.2
            }
        ]
    }
}
```

#### 4.2.2 针对特定商铺提问
```
POST /ai/shop/{shopId}/question
Content-Type: application/json

{
    "question": "这家店的招牌菜是什么？"
}
```

**响应示例：**
```json
{
    "code": 200,
    "msg": "success",
    "data": {
        "answer": "这家店的招牌菜是...",
        "shop": {
            "id": 1,
            "name": "xxx餐厅"
        }
    }
}
```

#### 4.2.3 初始化商铺知识库
```
POST /ai/shop/knowledge/init/{shopId}
```

**说明：** 将指定商铺的信息向量化存储到 Redis，支持 RAG 检索。

#### 4.2.4 初始化所有商铺知识库
```
POST /ai/shop/knowledge/init-all
```

**说明：** 批量将所有商铺信息向量化，建议在首次部署时执行。

---

## 五、使用示例

### 5.1 情感分析功能

```java
// 注入服务
@Resource
private ISentimentAnalysisService sentimentAnalysisService;

// 分析单条评论
ISentimentAnalysisService.Sentiment sentiment = sentimentAnalysisService.analyze("这家店太棒了！");
// sentiment = POSITIVE

// 获取商铺好评率
double rate = sentimentAnalysisService.getShopPositiveRate(shopId);
// rate = 0.85 (85%)

// 批量分析商铺所有评论
sentimentAnalysisService.analyzeBatch(shopId);
```

### 5.2 商铺问答功能

```java
// 注入服务
@Resource
private IShopQAService shopQAService;
@Resource
private IShopKnowledgeService shopKnowledgeService;

// 初始化商铺知识库（首次使用）
shopKnowledgeService.initializeAllShopKnowledge();

// 智能问答
Result result = shopQAService.answerShopQuestion(
    "附近有什么适合约会的餐厅？",
    121.5033,  // userX
    31.2374,   // userY
    5          // limit
);

// 针对特定商铺提问
Result result = shopQAService.answerQuestionAboutShop(shopId, "这家店人均多少？");
```

---

## 六、核心代码结构

### 6.1 新增文件清单

| 文件路径 | 说明 |
|---------|------|
| `config/AIConfig.java` | Spring AI 核心配置 |
| `config/SentimentConfig.java` | 情感分析配置 |
| `config/RagConfig.java` | RAG 配置 |
| `service/ISentimentAnalysisService.java` | 情感分析服务接口 |
| `service/impl/SentimentAnalysisServiceImpl.java` | 情感分析服务实现 |
| `service/IShopKnowledgeService.java` | 商铺知识库服务接口 |
| `service/impl/ShopKnowledgeServiceImpl.java` | 商铺知识库服务实现 |
| `service/IShopQAService.java` | 商铺问答服务接口 |
| `service/impl/ShopQAServiceImpl.java` | 商铺问答服务实现 |
| `controller/SentimentAnalysisController.java` | 情感分析 Controller |
| `controller/ShopQAController.java` | 商铺问答 Controller |
| `resources/application-ai.yaml` | AI 专用配置 |

### 6.2 修改文件清单

| 文件路径 | 修改内容 |
|---------|---------|
| `pom.xml` | 升级依赖版本，添加 Spring AI |
| `application.yaml` | 添加 `profiles.active: ai` |
| `MybatisConfig.java` | 简化配置 |
| `HmDianPingApplicationTests.java` | Mock Spring AI Bean |
| 多个 Controller/Service | `javax` → `jakarta` 迁移 |

---

## 七、注意事项

### 7.1 Redis VectorStore 说明
- 使用 Jedis 客户端连接 Redis
- 向量维度为 1536（text-embedding-3-small）
- 集合名称：`shop-knowledge-base`
- 前缀：`shop:vector:`

### 7.2 首次使用流程
1. 确保 Redis 服务正常运行
2. 配置 `OPENAI_API_KEY` 环境变量
3. 启动应用
4. 调用 `POST /ai/shop/knowledge/init-all` 初始化商铺知识库
5. 开始使用情感分析和问答功能

### 7.3 性能考虑
- 商铺知识库初始化为一次性操作，后续无需重复初始化
- 商铺信息更新后需重新初始化对应商铺的知识库
- 好评率统计结果会缓存，缓存过期后自动重新计算

### 7.4 错误处理
| 错误信息 | 可能原因 |
|---------|---------|
| `API Key 无效` | `OPENAI_API_KEY` 环境变量配置错误 |
| `VectorStore 初始化失败` | Redis 连接失败或 Jedis 依赖缺失 |
| `模型调用超时` | 网络问题或 API 限流 |

---

## 八、API 响应码

| 响应码 | 说明 |
|-------|------|
| 200 | 成功 |
| 400 | 请求参数错误 |
| 500 | 服务器内部错误 |

---

## 九、联系方式

如有问题，请检查：
1. 环境变量是否正确配置
2. Redis 服务是否正常运行
3. OpenAI API Key 是否有效
4. 端口 8081 是否被占用
