package com.hmdp.agent;

import com.hmdp.rag.query.RewriteQueryTransformer;
import com.hmdp.rag.retrieval.HybridDocumentRetriever;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * SearchKnowledgeBaseTool 单元测试 — 验证检索结果格式化。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SearchKnowledgeBaseTool 单元测试")
class SearchKnowledgeBaseToolTest {

    @Mock
    private HybridDocumentRetriever retriever;

    @Mock
    private RewriteQueryTransformer rewriter;

    @InjectMocks
    private SearchKnowledgeBaseTool tool;

    @BeforeEach
    void setUp() {
        // Rewrite pass-through for testing
        when(rewriter.transform(any(Query.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("CONFIDENT 结果 — 含文档列表和行动建议")
    void shouldFormatConfidentResults() {
        Document doc = new Document("雷峰塔 | 文化古迹 | 评分 4.3/5.0 | 西湖区",
                Map.of("spotId", "42"));
        when(retriever.retrieve(any(Query.class))).thenReturn(List.of(doc));
        when(retriever.getLastRetrievalConfidence()).thenReturn("CONFIDENT");
        when(retriever.getLastMaxRetrievalScore()).thenReturn(0.87);

        String result = tool.execute("{\"query\":\"雷峰塔\"}");

        assertThat(result).contains("[检索结果] 找到 1 篇文档");
        assertThat(result).contains("置信度: CONFIDENT");
        assertThat(result).contains("结果置信度高，可直接用于回答");
    }

    @Test
    @DisplayName("INSUFFICIENT 结果 — 含建议重新检索")
    void shouldFormatInsufficientResults() {
        when(retriever.retrieve(any(Query.class))).thenReturn(Collections.emptyList());
        when(retriever.getLastRetrievalConfidence()).thenReturn("INSUFFICIENT");
        when(retriever.getLastMaxRetrievalScore()).thenReturn(0.15);

        String result = tool.execute("{\"query\":\"不存在的景点\"}");

        assertThat(result).contains("找到 0 篇文档");
        assertThat(result).contains("INSUFFICIENT");
        assertThat(result).contains("用不同的关键词重新检索");
    }

    @Test
    @DisplayName("AMBIGUOUS 结果 — 含谨慎使用建议")
    void shouldFormatAmbiguousResults() {
        Document doc = new Document("一些部分匹配的内容", Map.of("spotId", "1"));
        when(retriever.retrieve(any(Query.class))).thenReturn(List.of(doc));
        when(retriever.getLastRetrievalConfidence()).thenReturn("AMBIGUOUS");
        when(retriever.getLastMaxRetrievalScore()).thenReturn(0.5);

        String result = tool.execute("{\"query\":\"测试\"}");

        assertThat(result).contains("AMBIGUOUS");
        assertThat(result).contains("谨慎回答");
    }

    @Test
    @DisplayName("空 query — 返回错误提示")
    void shouldHandleEmptyQuery() {
        String result = tool.execute("{\"query\":\"\"}");

        assertThat(result).contains("查询参数为空");
    }

    @Test
    @DisplayName("文档截断 — 超过 5 条显示省略提示")
    void shouldTruncateManyDocs() {
        List<Document> docs = java.util.stream.IntStream.range(1, 8)
                .mapToObj(i -> new Document("doc" + i, Map.of("spotId", String.valueOf(i))))
                .toList();
        when(retriever.retrieve(any(Query.class))).thenReturn(docs);
        when(retriever.getLastRetrievalConfidence()).thenReturn("CONFIDENT");
        when(retriever.getLastMaxRetrievalScore()).thenReturn(0.9);

        String result = tool.execute("{\"query\":\"杭州\"}");

        assertThat(result).contains("还有 2 篇文档");
    }
}
