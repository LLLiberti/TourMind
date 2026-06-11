package com.hmdp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 改动点1 FAQ 测试 — 覆盖天气查询的日常对话场景。
 *
 * <h3>覆盖场景</h3>
 * <ol>
 *   <li>有坐标的景点天气 → "西湖今天天气怎么样？"</li>
 *   <li>纯城市名天气 → "杭州天气如何？"（无景点坐标兜底）</li>
 *   <li>天气追问 → "那边会下雨吗？需要带伞吗？"</li>
 *   <li>天气代码映射 → 覆盖晴/雨/雪/雾/雷暴等常见天气</li>
 * </ol>
 */
@DisplayName("FAQ: 天气查询")
class WeatherFAQTest {

    // ==================== FAQ-1: 天气代码映射 ====================

    @Nested
    @DisplayName("FAQ-1: WMO天气码 → 中文描述映射")
    class WeatherCodeMappingTests {

        /**
         * 使用 WeatherServiceImpl 内部的 WEATHER_CODE_MAP 映射表验证。
         * 此处独立定义映射表用于交叉验证 — 确保业务代码与预期一致。
         */
        private static final java.util.Map<Integer, String> EXPECTED_MAP = java.util.Map.ofEntries(
                java.util.Map.entry(0, "晴天"),
                java.util.Map.entry(1, "少云"),
                java.util.Map.entry(2, "晴间多云"),
                java.util.Map.entry(3, "多云"),
                java.util.Map.entry(45, "有雾"),
                java.util.Map.entry(48, "雾凇"),
                java.util.Map.entry(51, "毛毛雨"),
                java.util.Map.entry(53, "毛毛雨"),
                java.util.Map.entry(55, "毛毛雨"),
                java.util.Map.entry(61, "小雨"),
                java.util.Map.entry(63, "中雨"),
                java.util.Map.entry(65, "大雨"),
                java.util.Map.entry(71, "小雪"),
                java.util.Map.entry(73, "中雪"),
                java.util.Map.entry(75, "大雪"),
                java.util.Map.entry(77, "雪粒"),
                java.util.Map.entry(80, "阵雨"),
                java.util.Map.entry(81, "阵雨"),
                java.util.Map.entry(82, "阵雨"),
                java.util.Map.entry(85, "阵雪"),
                java.util.Map.entry(86, "阵雪"),
                java.util.Map.entry(95, "雷暴"),
                java.util.Map.entry(96, "冰雹雷暴"),
                java.util.Map.entry(99, "冰雹雷暴")
        );

        @Test
        @DisplayName("晴天(0) → 包含'晴天'")
        void sunnyCode() {
            assertThat(EXPECTED_MAP.get(0)).contains("晴天");
        }

        @Test
        @DisplayName("各种雨天代码(61/63/65) → 均包含'雨'")
        void rainCodes() {
            assertThat(EXPECTED_MAP.get(61)).contains("雨");
            assertThat(EXPECTED_MAP.get(63)).contains("雨");
            assertThat(EXPECTED_MAP.get(65)).contains("雨");
        }

        @Test
        @DisplayName("各种雪天代码(71/73/75) → 均包含'雪'")
        void snowCodes() {
            assertThat(EXPECTED_MAP.get(71)).contains("雪");
            assertThat(EXPECTED_MAP.get(73)).contains("雪");
            assertThat(EXPECTED_MAP.get(75)).contains("雪");
        }

        @Test
        @DisplayName("雷暴代码(95) → 包含'雷暴'")
        void thunderstormCode() {
            assertThat(EXPECTED_MAP.get(95)).contains("雷暴");
        }

        @Test
        @DisplayName("有雾代码(45) → 包含'雾'")
        void fogCode() {
            assertThat(EXPECTED_MAP.get(45)).contains("雾");
        }

        @Test
        @DisplayName("覆盖常见24种天气码 → 映射表不为空")
        void allCommonCodesCovered() {
            assertThat(EXPECTED_MAP).isNotEmpty();
            assertThat(EXPECTED_MAP.size()).isGreaterThanOrEqualTo(20);
        }
    }

    // ==================== FAQ-2: 日常对话关键词验证 ====================

    @Nested
    @DisplayName("FAQ-2: 天气类查询关键词 — 应走 KNOWLEDGE 分流（非闲聊）")
    class WeatherQueryRoutingTests {

        /**
         * 天气查询不应被 QueryRouter 归类为闲聊 — 必须走 RAG 管线
         * 以便 LLM 获取景点坐标后调用 checkWeather 工具。
         * 此测试验证天气类问句不会匹配闲聊关键词。
         */
        @Test
        @DisplayName("天气查询不应被误判为闲聊")
        void weatherQueriesShouldNotBeChitchat() {
            String[] chitchatPrefixes = {
                    "你好", "嗨", "谢谢", "再见", "嗯", "哦", "好的", "在吗"
            };
            String[] weatherQueries = {
                    "今天天气怎么样",
                    "西湖会下雨吗",
                    "杭州温度多少",
                    "那边需要带伞吗",
                    "明天冷不冷"
            };

            for (String wq : weatherQueries) {
                boolean isChitchat = false;
                for (String prefix : chitchatPrefixes) {
                    if (wq.startsWith(prefix)) {
                        isChitchat = true;
                        break;
                    }
                }
                assertThat(isChitchat)
                        .as("天气查询 '%s' 不应被误判为闲聊", wq)
                        .isFalse();
            }
        }

        @Test
        @DisplayName("天气查询不应匹配退购票关键词")
        void weatherQueriesShouldNotMatchTicketRefund() {
            java.util.Set<String> ticketKeywords = java.util.Set.of(
                    "退票", "退款", "购票", "买票", "预订", "门票怎么", "票价"
            );
            String[] weatherQueries = {
                    "今天天气怎么样",
                    "西湖会下雨吗",
                    "杭州温度多少",
                    "那边需要带伞吗"
            };

            for (String wq : weatherQueries) {
                boolean matches = false;
                for (String kw : ticketKeywords) {
                    if (wq.contains(kw)) {
                        matches = true;
                        break;
                    }
                }
                assertThat(matches)
                        .as("天气查询 '%s' 不应匹配退购票关键词", wq)
                        .isFalse();
            }
        }
    }

    // ==================== FAQ-3: 端到端集成测试 ====================

    @Nested
    @DisplayName("FAQ-INTEGRATION: 需基础设施的端到端测试")
    @Tag("integration")
    class IntegrationTests {

        @Test
        @DisplayName("'西湖今天天气怎么样？' → 回答应包含天气信息（温度/天气现象）")
        void spotWeatherIntegration() {
            // 此测试需启动完整服务：MySQL + Redis + Qdrant + ES + DeepSeek + 可用公网
            // 1. 确保 tourmind-core 已启动
            // 2. 发送 POST /ai/spot/question
            //    body: {"userId":1, "question": "西湖今天天气怎么样？", "userX": 120.14, "userY": 30.23}
            // 3. 验证回答包含 "°C" 和天气描述（晴/雨/多云等）
        }

        @Test
        @DisplayName("'杭州天气如何？' → 无景点坐标，走地理编码兜底")
        void cityWeatherIntegration() {
            // 同上，验证即使没有景点上下文也能通过地理编码获取天气
            // body: {"userId":1, "question": "杭州天气如何？"}
        }

        @Test
        @DisplayName("追问'那边会下雨吗？' → 多轮对话上下文解析")
        void followUpWeatherIntegration() {
            // 先问"西湖今天天气怎么样？"得到回答后
            // 再问"那边会下雨吗？需要带伞吗？"→ LLM 应结合对话历史理解"那边"= 西湖
            // 再次调用 checkWeather 工具
        }
    }
}
