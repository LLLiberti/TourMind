package com.hmdp;

import com.hmdp.rag.chunking.SpotKnowledgeSplitter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SpotKnowledgeSplitter 单元测试。
 */
@DisplayName("SpotKnowledgeSplitter")
class SpotKnowledgeSplitterTest {

    private SpotKnowledgeSplitter splitter;
    private Map<String, Object> parentMetadata;

    @BeforeEach
    void setUp() {
        splitter = new SpotKnowledgeSplitter();
        parentMetadata = new HashMap<>();
        parentMetadata.put("spotId", "123");
        parentMetadata.put("spotName", "西湖");
    }

    @Nested
    @DisplayName("split - 正常切分")
    class NormalSplitTests {

        @Test
        @DisplayName("应将三段落文本切分为 3 个 chunk")
        void shouldSplitIntoThreeChunks() {
            String text = """
                [景点简介]
                景点名称：西湖
                景点类型：自然风景区
                评分：4.5/5.0分

                [位置交通]
                所在区域：西湖区
                具体地址：杭州市西湖区西湖风景区

                [开放须知]
                开放时间：全天""";

            Document doc = new Document(text, parentMetadata);
            List<Document> chunks = splitter.split(doc);

            assertThat(chunks).hasSize(3);
        }

        @Test
        @DisplayName("每个 chunk 应继承父元数据（spotId、spotName）")
        void shouldInheritParentMetadata() {
            String text = """
                [景点简介]
                西湖简介内容

                [开放须知]
                须知内容""";

            Document doc = new Document(text, parentMetadata);
            List<Document> chunks = splitter.split(doc);

            for (Document chunk : chunks) {
                assertThat(chunk.getMetadata()).containsEntry("spotId", "123");
                assertThat(chunk.getMetadata()).containsEntry("spotName", "西湖");
            }
        }

        @Test
        @DisplayName("每个 chunk 应包含 chunkTopic 和 chunkIndex")
        void shouldAddChunkMetadata() {
            String text = """
                [景点简介]
                西湖简介内容

                [开放须知]
                须知内容""";

            Document doc = new Document(text, parentMetadata);
            List<Document> chunks = splitter.split(doc);

            assertThat(chunks.get(0).getMetadata()).containsKey("chunkTopic");
            assertThat(chunks.get(0).getMetadata()).containsEntry("chunkIndex", 0);
            assertThat(chunks.get(1).getMetadata()).containsEntry("chunkIndex", 1);
        }

        @Test
        @DisplayName("chunkTopic 应提取标签中的文本")
        void shouldExtractCorrectTopic() {
            String text = """
                [景点简介]
                简介内容""";

            Document doc = new Document(text, parentMetadata);
            List<Document> chunks = splitter.split(doc);

            assertThat(chunks.get(0).getMetadata()).containsEntry("chunkTopic", "简介");
        }

        @Test
        @DisplayName("chunk 内容不应互相包含")
        void chunksShouldNotOverlap() {
            String text = """
                [景点简介]
                简介内容

                [开放须知]
                须知内容""";

            Document doc = new Document(text, parentMetadata);
            List<Document> chunks = splitter.split(doc);

            assertThat(chunks.get(0).getText()).contains("简介内容");
            assertThat(chunks.get(0).getText()).doesNotContain("须知内容");
            assertThat(chunks.get(1).getText()).contains("须知内容");
        }
    }

    @Nested
    @DisplayName("split - 兼容性")
    class CompatibilityTests {

        @Test
        @DisplayName("无标题标记时应返回原 Document（不切分）")
        void shouldReturnOriginalWhenNoHeadings() {
            String text = "这是一段没有标签的普通文本，内容随意。";

            Document doc = new Document(text, parentMetadata);
            List<Document> chunks = splitter.split(doc);

            assertThat(chunks).hasSize(1);
            assertThat(chunks.get(0)).isSameAs(doc);
        }

        @Test
        @DisplayName("空文本应返回空列表")
        void shouldReturnEmptyForBlankText() {
            Document doc = new Document("", parentMetadata);
            List<Document> chunks = splitter.split(doc);

            assertThat(chunks).isEmpty();
        }
    }
}
