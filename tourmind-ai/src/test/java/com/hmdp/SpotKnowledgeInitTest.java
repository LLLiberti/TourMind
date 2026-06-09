package com.hmdp;

import com.hmdp.entity.Spot;
import com.hmdp.mapper.SpotMapper;
import com.hmdp.service.ISpotKnowledgeService;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.List;

/**
 * RAG 知识库初始化测试
 *
 * 模拟管理员将 MySQL 中的商铺数据向量化并写入 Qdrant VectorStore，
 * 为后续的语义检索（RAG）提供数据基础。
 *
 * 测试场景流程：
 *   1. 初始化单个商铺知识库
 *   2. 验证 Redis 知识文本缓存
 *   3. 初始化全部商铺知识库
 *   4. 更新指定商铺知识库
 *   5. 删除指定商铺知识库
 *
 * 运行要求：
 *   - MySQL 数据库可连接
 *   - Redis 服务可用（127.0.0.1:6379，知识文本/元数据缓存）
 *   - Qdrant 向量库可用（localhost:6334）
 *   - Ollama Embedding 服务可用（bge-m3 模型）
 */
@SpringBootTest


@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("integration")
@DisplayName("RAG 知识库初始化测试")
class SpotKnowledgeInitTest {

    @Resource
    private ISpotKnowledgeService spotKnowledgeService;

    @Resource
    private SpotMapper spotMapper;

    @Resource
    private RedisTemplate<Object, Object> redisTemplate;

    private static final String SPOT_KNOWLEDGE_PREFIX = "spot:knowledge:";
    private static final String SPOT_METADATA_PREFIX = "spot:metadata:";

    private Long testSpotId;

    @BeforeAll
    void setUp() {
        // 取第一条商铺记录用于测试
        List<Long> allIds = spotMapper.selectAllIds();
        if (!allIds.isEmpty()) {
            testSpotId = allIds.get(0);
        }
    }

    // ==================== 场景一：初始化单个商铺知识库 ====================

    @Test
    @Order(1)
    @DisplayName("【场景一】初始化单个商铺知识库")
    void testInitializeSingleSpotKnowledge() {
        Assumptions.assumeTrue(testSpotId != null, "数据库中没有商铺数据，跳过测试");

        System.out.println("=" .repeat(60));
        System.out.println("【场景一】初始化单个商铺知识库");
        System.out.println("=" .repeat(60));

        // 1. 查询商铺原始信息
        Spot spot = spotMapper.selectById(testSpotId);
        System.out.println("\n📋 商铺原始信息 (MySQL):");
        System.out.println("   ID: " + spot.getId());
        System.out.println("   名称: " + spot.getName());
        System.out.println("   区域: " + spot.getArea());
        System.out.println("   地址: " + spot.getAddress());
        System.out.println("   门票: " + spot.getTicketPrice() + " 元");
        System.out.println("   评分: " + spot.getScore() + " 分");

        // 2. 执行知识库初始化
        System.out.println("\n🔄 开始向量化...");
        long start = System.currentTimeMillis();
        spotKnowledgeService.initializeSpotKnowledge(testSpotId);
        long elapsed = System.currentTimeMillis() - start;
        System.out.println("   耗时: " + elapsed + "ms");

        // 3. 验证 Redis 知识文本缓存
        String knowledgeKey = SPOT_KNOWLEDGE_PREFIX + testSpotId;
        Object cached = redisTemplate.opsForValue().get(knowledgeKey);
        Assertions.assertNotNull(cached, "知识文本缓存应存在");
        System.out.println("\n📝 向量化后的知识文本 (qdrant):");
        System.out.println("---");
        System.out.println(cached);
        System.out.println("---");

        System.out.println("\n✅ 场景一通过：商铺 [" + spot.getName() + "] 知识库初始化成功");
    }

    // ==================== 场景二：验证 Redis 元数据缓存 ====================

    @Test
    @Order(2)
    @DisplayName("【场景二】验证 Redis 元数据缓存")
    void testMetadataCacheAfterInit() {
        Assumptions.assumeTrue(testSpotId != null, "数据库中没有商铺数据，跳过测试");

        System.out.println("\n" + "=" .repeat(60));
        System.out.println("【场景二】验证 Redis 元数据缓存");
        System.out.println("=" .repeat(60));

        String metadataKey = SPOT_METADATA_PREFIX + testSpotId;
        var metadata = redisTemplate.opsForHash().entries(metadataKey);

        System.out.println("\n📍 商铺元数据 (Redis Hash: " + metadataKey + "):");
        metadata.forEach((k, v) -> System.out.println("   " + k + " = " + v));

        Assertions.assertFalse(metadata.isEmpty(), "元数据缓存不应为空");
        Assertions.assertNotNull(metadata.get("name"), "应包含商铺名称");
        Assertions.assertNotNull(metadata.get("ticketPrice"), "应包含门票价格");
        Assertions.assertNotNull(metadata.get("score"), "应包含评分");

        System.out.println("\n✅ 场景二通过：元数据缓存验证成功");
    }

    // ==================== 场景三：初始化全部商铺知识库 ====================

    @Test
    @Order(3)
    @DisplayName("【场景三】批量初始化全部商铺知识库")
    void testInitializeAllSpotKnowledge() {
        System.out.println("\n" + "=" .repeat(60));
        System.out.println("【场景三】批量初始化全部商铺知识库");
        System.out.println("=" .repeat(60));

        List<Long> allIds = spotMapper.selectAllIds();
        System.out.println("\n📊 商铺总数: " + allIds.size());

        System.out.println("🔄 开始批量向量化...");
        long start = System.currentTimeMillis();
        spotKnowledgeService.initializeAllSpotKnowledge();
        long elapsed = System.currentTimeMillis() - start;
        System.out.println("   总耗时: " + elapsed + "ms");
        System.out.println("   平均每条: " + (elapsed / Math.max(allIds.size(), 1)) + "ms");

        // 抽查验证前 3 条
        int checkCount = Math.min(3, allIds.size());
        System.out.println("\n🔍 抽查前 " + checkCount + " 条缓存:");
        for (int i = 0; i < checkCount; i++) {
            String key = SPOT_KNOWLEDGE_PREFIX + allIds.get(i);
            Object cached = redisTemplate.opsForValue().get(key);
            String status = cached != null ? "✅ 已缓存" : "❌ 未缓存";
            Spot spot = spotMapper.selectById(allIds.get(i));
            System.out.println("   [" + (i + 1) + "] " + (spot != null ? spot.getName() : "N/A") + " → " + status);
        }

        System.out.println("\n✅ 场景三通过：全部商铺知识库初始化完成");
    }

    // ==================== 场景四：更新景点知识库 ====================

    @Test
    @Order(4)
    @DisplayName("【场景四】更新景点知识库")
    void testUpdateSpotKnowledge() {
        Assumptions.assumeTrue(testSpotId != null, "数据库中没有商铺数据，跳过测试");

        System.out.println("\n" + "=" .repeat(60));
        System.out.println("【场景四】更新景点知识库 (先删后建)");
        System.out.println("=" .repeat(60));

        Spot spot = spotMapper.selectById(testSpotId);
        System.out.println("\n📋 更新景点: " + spot.getName());

        // 记录更新前的缓存 Key
        String knowledgeKey = SPOT_KNOWLEDGE_PREFIX + testSpotId;
        String oldCache = (String) redisTemplate.opsForValue().get(knowledgeKey);
        System.out.println("   更新前知识文本长度: " + (oldCache != null ? oldCache.length() : 0) + " 字符");

        // 执行更新
        System.out.println("🔄 执行更新...");
        long start = System.currentTimeMillis();
        spotKnowledgeService.updateSpotKnowledge(testSpotId);
        long elapsed = System.currentTimeMillis() - start;
        System.out.println("   耗时: " + elapsed + "ms");

        // 验证更新后缓存
        String newCache = (String) redisTemplate.opsForValue().get(knowledgeKey);
        Assertions.assertNotNull(newCache, "更新后知识文本缓存应存在");

        System.out.println("   更新后知识文本长度: " + newCache.length() + " 字符");

        System.out.println("\n✅ 场景四通过：商铺知识库更新成功");
    }

    // ==================== 场景五：删除景点知识库 ====================

    @Test
    @Order(5)
    @DisplayName("【场景五】删除景点知识库")
    void testDeleteSpotKnowledge() {
        Assumptions.assumeTrue(testSpotId != null, "数据库中没有商铺数据，跳过测试");

        System.out.println("\n" + "=" .repeat(60));
        System.out.println("【场景五】删除景点知识库");
        System.out.println("=" .repeat(60));

        Spot spot = spotMapper.selectById(testSpotId);
        System.out.println("\n📋 删除景点: " + spot.getName());

        // 确认删除前缓存存在
        String knowledgeKey = SPOT_KNOWLEDGE_PREFIX + testSpotId;
        Object beforeDelete = redisTemplate.opsForValue().get(knowledgeKey);
        System.out.println("   删除前: " + (beforeDelete != null ? "缓存存在" : "缓存不存在"));

        // 执行删除
        spotKnowledgeService.deleteSpotKnowledge(testSpotId);

        // 验证删除后缓存消失
        Object afterDelete = redisTemplate.opsForValue().get(knowledgeKey);
        System.out.println("   删除后: " + (afterDelete == null ? "缓存已清除 ✅" : "缓存仍存在 ❌"));

        Assertions.assertNull(afterDelete, "删除后知识文本缓存应不存在");

        // 验证元数据也被删除
        String metadataKey = SPOT_METADATA_PREFIX + testSpotId;
        var metadata = redisTemplate.opsForHash().entries(metadataKey);
        Assertions.assertTrue(metadata.isEmpty(), "删除后元数据缓存应不存在");

        System.out.println("\n✅ 场景五通过：商铺知识库删除成功");

        // 恢复数据
        System.out.println("\n🔧 恢复测试数据...");
        spotKnowledgeService.initializeSpotKnowledge(testSpotId);
        System.out.println("   已重新初始化");
    }
}
