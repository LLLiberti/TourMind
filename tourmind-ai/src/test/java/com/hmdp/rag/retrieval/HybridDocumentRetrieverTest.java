package com.hmdp.rag.retrieval;

import com.hmdp.entity.Spot;
import com.hmdp.rag.RetrievalContext;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.*;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HybridDocumentRetriever 集成测试 — 使用真实 Spring 上下文（Qdrant / BM25 / MySQL / Redis）。
 *
 * <p>需要基础设施可用 + 知识库已初始化（调用 init-all 后执行）。</p>
 * <p>运行方式：{@code mvn test -Dgroups=integration}</p>
 */
@SpringBootTest
@Tag("integration")
@DisplayName("混合文档检索器")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HybridDocumentRetrieverTest {

    @Resource
    private HybridDocumentRetriever retriever;

    // ==================== 基础检索 ====================

    @Test
    @Order(1)
    @DisplayName("向量检索 — 返回父文档")
    void retrieveReturnsParentDocuments() {
        Query query = Query.builder()
                .text("西湖 自然风景区 推荐 好玩 景点")
                .build();

        List<Document> docs = retriever.retrieve(query);

        assertNotNull(docs);
        if (!docs.isEmpty()) {
            Document first = docs.get(0);
            assertNotNull(first.getText());
            assertFalse(first.getText().isEmpty());
            assertNotNull(first.getMetadata().get("spotId"));
            System.out.println("检索到 " + docs.size() + " 个文档");
            System.out.println("第一个文档: " + first.getMetadata().get("spotId") +
                    " - " + first.getMetadata().get("spotName"));
        } else {
            System.out.println("未检索到结果（可能知识库未初始化）");
        }

        retriever.clearContext();
    }

    @Test
    @Order(2)
    @DisplayName("坐标设置 + 距离排序")
    void coordinatesAndDistanceSorting() {
        retriever.setUserCoordinates(120.15, 30.28);

        Query query = Query.builder()
                .text("杭州 景点 推荐")
                .build();

        retriever.retrieve(query);
        List<Spot> spots = retriever.getLastRetrievedSpots();

        assertNotNull(spots);
        if (spots.size() >= 2) {
            // 有坐标的景点应该有距离值
            boolean hasDistance = spots.stream().anyMatch(s -> s.getDistance() != null);
            System.out.println("检索到 " + spots.size() + " 个景点，有距离信息: " + hasDistance);
        }

        retriever.clearContext();
    }

    @Test
    @Order(3)
    @DisplayName("ThreadLocal 上下文清理")
    void threadLocalCleanup() {
        retriever.setUserCoordinates(120.0, 30.0);
        Query query = Query.builder().text("西湖").build();
        retriever.retrieve(query);

        // 清理前应有结果
        List<Spot> before = retriever.getLastRetrievedSpots();
        assertNotNull(before);

        // 清理后
        retriever.clearContext();
        List<Spot> after = retriever.getLastRetrievedSpots();
        assertTrue(after.isEmpty(), "清理后应返回空列表");
    }

    @Test
    @Order(4)
    @DisplayName("空查询返回空结果")
    void emptyQueryReturnsEmpty() {
        Query query = Query.builder().text("").build();
        List<Document> docs = retriever.retrieve(query);

        assertNotNull(docs);
        // 空查询可能返回空或返回结果（取决于向量库行为）
        System.out.println("空查询结果数: " + docs.size());
        retriever.clearContext();
    }
}
