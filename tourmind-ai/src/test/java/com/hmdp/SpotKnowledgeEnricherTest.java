package com.hmdp;

import com.hmdp.entity.Spot;
import com.hmdp.rag.chunking.SpotKnowledgeEnricher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SpotKnowledgeEnricher 单元测试。
 */
@DisplayName("SpotKnowledgeEnricher")
class SpotKnowledgeEnricherTest {

    private SpotKnowledgeEnricher enricher;
    private Spot spot;

    @BeforeEach
    void setUp() {
        enricher = new SpotKnowledgeEnricher();
        spot = new Spot();
        spot.setId(1L);
        spot.setName("西湖");
        spot.setArea("西湖区");
        spot.setAddress("杭州市西湖区西湖风景区");
        spot.setScore(45);  // 45/10 = 4.5
        spot.setTicketPrice(8000L);  // 80.00 元
        spot.setOpenHours("全天");
        spot.setX(120.15);
        spot.setY(30.27);
    }

    @Nested
    @DisplayName("enrich - 富化文本格式")
    class EnrichFormatTests {

        @Test
        @DisplayName("应包含三个语义标签：简介、位置、须知")
        void shouldContainThreeSemanticSections() {
            String result = enricher.enrich(spot, "自然风景区");

            assertThat(result).contains("[景点简介]");
            assertThat(result).contains("[位置交通]");
            assertThat(result).contains("[开放须知]");
        }

        @Test
        @DisplayName("应包含景点名称和类型")
        void shouldContainNameAndType() {
            String result = enricher.enrich(spot, "自然风景区");

            assertThat(result).contains("景点名称：西湖");
            assertThat(result).contains("景点类型：自然风景区");
        }

        @Test
        @DisplayName("评分应从整数 45 转换为 4.5/5.0分")
        void shouldFormatScoreCorrectly() {
            String result = enricher.enrich(spot, "自然风景区");

            assertThat(result).contains("4.5/5.0分");
            assertThat(result).doesNotContain("45分");  // 不应出现原始整数
        }

        @Test
        @DisplayName("应包含地理坐标（经度+纬度）")
        void shouldContainCoordinates() {
            String result = enricher.enrich(spot, "自然风景区");

            assertThat(result).contains("东经120.1500°");
            assertThat(result).contains("北纬30.2700°");
        }

        @Test
        @DisplayName("应包含开放时间")
        void shouldContainOpenHours() {
            String result = enricher.enrich(spot, "自然风景区");

            assertThat(result).contains("开放时间：全天");
        }
    }

    @Nested
    @DisplayName("enrich - 实时数据排除")
    class RealTimeDataExclusionTests {

        @Test
        @DisplayName("不应包含门票价格")
        void shouldNotContainTicketPrice() {
            String result = enricher.enrich(spot, "自然风景区");

            assertThat(result).doesNotContain("门票价格");
            assertThat(result).doesNotContain("80.00");
            assertThat(result).doesNotContain("8000");
        }
    }

    @Nested
    @DisplayName("enrich - 边界情况")
    class EdgeCaseTests {

        @Test
        @DisplayName("score 为 null 时应显示暂无评分")
        void shouldHandleNullScore() {
            spot.setScore(null);

            String result = enricher.enrich(spot, "自然风景区");

            assertThat(result).contains("暂无评分");
        }

        @Test
        @DisplayName("openHours 为 null 时应显示默认文案")
        void shouldHandleNullOpenHours() {
            spot.setOpenHours(null);

            String result = enricher.enrich(spot, "自然风景区");

            assertThat(result).contains("请咨询景区");
        }

        @Test
        @DisplayName("area 为 null 时不应包含所在区域行")
        void shouldHandleNullArea() {
            spot.setArea(null);
            spot.setAddress(null);

            String result = enricher.enrich(spot, "自然风景区");

            assertThat(result).doesNotContain("所在区域");
            assertThat(result).doesNotContain("具体地址");
        }

        @Test
        @DisplayName("无坐标时不应包含地理坐标行")
        void shouldHandleNullCoordinates() {
            spot.setX(null);
            spot.setY(null);

            String result = enricher.enrich(spot, "自然风景区");

            assertThat(result).doesNotContain("东经");
            assertThat(result).doesNotContain("北纬");
        }

        @Test
        @DisplayName("typeName 为 null 时应显示未知")
        void shouldHandleNullTypeName() {
            String result = enricher.enrich(spot, null);

            assertThat(result).contains("景点类型：未知");
        }
    }
}
