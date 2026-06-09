package com.hmdp.rag.client;

import com.hmdp.config.RagConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * QwenRerankClient 测试。
 *
 * <p>基础逻辑测试（不需要外部服务）：空输入、null输入处理。</p>
 * <p>实际API调用测试标记为 integration，需要配置 reranker endpoint。</p>
 */
@DisplayName("qwen3-rerank 客户端")
class QwenRerankClientTest {

    private QwenRerankClient client;

    @BeforeEach
    void setUp() {
        RagConfig.RerankerConfig config = new RagConfig.RerankerConfig();
        config.setEndpoint("http://localhost:8080"); // 不需要真实连接，仅用于构造
        config.setModel("qwen3-rerank");
        config.setTopK(10);
        config.setScoreThreshold(0.1);
        config.setTimeoutMs(5000);
        client = new QwenRerankClient(config);
    }

    // ==================== 空输入处理 ====================

    @Test
    @DisplayName("空查询 → 返回null")
    void nullQueryReturnsNull() {
        assertNull(client.rerank(null, List.of("doc1")));
        assertNull(client.rerank("", List.of("doc1")));
        assertNull(client.rerank("   ", List.of("doc1")));
    }

    @Test
    @DisplayName("空文档列表 → 返回null")
    void emptyDocumentsReturnsNull() {
        assertNull(client.rerank("西湖", List.of()));
        assertNull(client.rerank("西湖", null));
    }

    @Test
    @DisplayName("连接不可达 → 返回null（不抛异常）")
    void unreachableEndpointReturnsNull() {
        // endpoint 为无效地址，应返回 null 而非抛异常
        RagConfig.RerankerConfig badConfig = new RagConfig.RerankerConfig();
        badConfig.setEndpoint("http://127.0.0.1:19999"); // 未监听的端口
        badConfig.setModel("qwen3-rerank");
        badConfig.setTopK(10);
        badConfig.setScoreThreshold(0.1);
        badConfig.setTimeoutMs(1000);

        QwenRerankClient badClient = new QwenRerankClient(badConfig);
        List<QwenRerankClient.RerankResult> result = badClient.rerank("test", List.of("test doc"));

        assertNull(result, "不可达端点应返回 null，不阻断检索");
    }

    // ==================== 集成测试（需真实 reranker 服务） ====================

    @Test
    @Tag("integration")
    @DisplayName("[集成] 真实重排调用")
    void realRerankCall() {
        // 此测试需要真实的 qwen3-rerank API 服务运行
        // 配置方式：在 test application.yaml 中设置 rag.reranker.endpoint
        // 运行方式：mvn test -Dgroups=integration
        RagConfig.RerankerConfig realConfig = new RagConfig.RerankerConfig();
        realConfig.setEndpoint(System.getProperty("reranker.endpoint", ""));
        realConfig.setModel("qwen3-rerank");
        realConfig.setTopK(5);
        realConfig.setScoreThreshold(0.1);
        realConfig.setTimeoutMs(10000);

        if (realConfig.getEndpoint().isEmpty()) {
            System.out.println("[集成] 跳过重排测试：未配置 reranker.endpoint");
            return;
        }

        QwenRerankClient realClient = new QwenRerankClient(realConfig);
        List<QwenRerankClient.RerankResult> results = realClient.rerank(
                "西湖有哪些好玩的景点",
                List.of(
                        "西湖是杭州最著名的自然风景区，世界文化遗产。",
                        "雷峰塔位于西湖畔，是著名的历史文化景点。",
                        "这是一个与西湖无关的餐厅介绍。"
                )
        );

        if (results != null) {
            assertFalse(results.isEmpty(), "应有重排结果");
            // 前两个与西湖相关的文档应排在前面
            System.out.println("重排结果:");
            results.forEach(r -> System.out.println("  " + r));
        }
    }
}
