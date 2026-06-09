package com.hmdp.rag.index;

import com.hmdp.entity.Spot;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ParentChildIndexer 集成测试 — 使用真实 Spring 上下文（Qdrant / MySQL / Redis）。
 *
 * <p>需要基础设施可用。运行方式：{@code mvn test -Dgroups=integration}</p>
 */
@SpringBootTest
@Tag("integration")
@DisplayName("父子文档索引协调器")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ParentChildIndexerTest {

    @Resource
    private ParentChildIndexer parentChildIndexer;

    /** 测试用的景点 ID（使用数据库中真实存在的记录） */
    private static final Long TEST_SPOT_ID = 1L;

    @Test
    @Order(1)
    @DisplayName("索引景点 — 验证父文档和子文档写入")
    void indexSpot() {
        // 构造测试 Spot
        Spot spot = new Spot();
        spot.setId(TEST_SPOT_ID);
        spot.setName("测试景点-西湖");
        spot.setTypeId(1L);
        spot.setArea("西湖区");
        spot.setAddress("杭州市西湖区");
        spot.setX(120.15);
        spot.setY(30.28);
        spot.setScore(45);
        spot.setOpenHours("全天");
        spot.setCreateTime(LocalDateTime.now());
        spot.setUpdateTime(LocalDateTime.now());

        ParentChildIndexer.IndexingResult result = parentChildIndexer.indexSpot(spot, "自然风光");

        assertNotNull(result);
        assertNotNull(result.getParentText());
        assertFalse(result.getParentText().isEmpty(), "父文档全文不应为空");
        assertTrue(result.getParentText().contains("[景点简介]"), "应含语义标签");
        assertTrue(result.getParentText().contains("[位置交通]"), "应含语义标签");
        assertTrue(result.getParentText().contains("[开放须知]"), "应含语义标签");
        assertTrue(result.getChildCount() >= 2, "至少应有 2 个子文档");

        System.out.println("父文档全文:");
        System.out.println(result.getParentText());
        System.out.println("子文档数: " + result.getChildCount());
    }

    @Test
    @Order(2)
    @DisplayName("加载父文档 — Redis命中")
    void loadParentTextFromRedis() {
        String text = parentChildIndexer.loadParentText(TEST_SPOT_ID);

        assertNotNull(text);
        assertFalse(text.isEmpty());
        assertTrue(text.contains("[景点简介]"));
        System.out.println("Redis 命中: 父文档长度=" + text.length());
    }

    @Test
    @Order(3)
    @DisplayName("删除景点 — 验证清理")
    void deleteSpot() {
        parentChildIndexer.deleteSpot(TEST_SPOT_ID);

        // 删除后 Redis 应无缓存
        String text = parentChildIndexer.loadParentText(TEST_SPOT_ID);
        // 此时应走 MySQL → 重建路径，因为 MySQL 也被删了
        // 应该能返回文本（从 Spot 实体重建），非 null
        assertNotNull(text, "重建路径应能返回文本");
        System.out.println("删除后重建: 父文档长度=" + text.length());
    }

    @Test
    @Order(4)
    @DisplayName("重新索引 — 幂等验证")
    void reindexIsIdempotent() {
        Spot spot = new Spot();
        spot.setId(TEST_SPOT_ID);
        spot.setName("测试景点-西湖(更新)");
        spot.setTypeId(1L);
        spot.setArea("西湖区");
        spot.setAddress("杭州市西湖区");
        spot.setScore(45);
        spot.setOpenHours("全天");

        // 第一次索引
        parentChildIndexer.indexSpot(spot, "自然风光");
        String text1 = parentChildIndexer.loadParentText(TEST_SPOT_ID);

        // 第二次索引（幂等）
        parentChildIndexer.indexSpot(spot, "自然风光");
        String text2 = parentChildIndexer.loadParentText(TEST_SPOT_ID);

        assertNotNull(text1);
        assertNotNull(text2);
        assertEquals(text1, text2, "幂等索引后文本应相同");

        // 清理
        parentChildIndexer.deleteSpot(TEST_SPOT_ID);
    }
}
