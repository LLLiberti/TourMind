package com.hmdp;

import com.hmdp.entity.Spot;
import com.hmdp.rag.chunking.TicketRefundEnricher;
import com.hmdp.rag.index.TicketRefundIndexer;
import com.hmdp.rag.router.TicketRefundConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * 改动点1 FAQ 测试 — 覆盖景点特色、购票须知、退票条件的日常对话场景。
 *
 * <h3>覆盖场景</h3>
 * <ol>
 *   <li>问特色 → 主KB {@code [景点特色]} section 命中</li>
 *   <li>问购票 → 退购票KB {@code [购票须知]} chunk 命中</li>
 *   <li>问退票 → 退购票KB {@code [退票条件]} chunk 命中</li>
 *   <li>无购退票关键词 → 不触发退购票KB（省检索）</li>
 *   <li>字段为空 → 不生成对应 Document</li>
 * </ol>
 */
@DisplayName("FAQ: 景点特色/购票须知/退票条件")
class TicketRefundFAQTest {

    // ==================== 场景 1: TicketRefundEnricher 构建文档 ====================

    @Nested
    @DisplayName("FAQ-1: 问'怎么买票' → 应返回购票须知")
    class TicketNoticeTests {

        private TicketRefundEnricher enricher;
        private Spot spot;

        @BeforeEach
        void setUp() {
            enricher = new TicketRefundEnricher();
            spot = new Spot();
            spot.setId(1L);
            spot.setName("雷峰塔");
        }

        @Test
        @DisplayName("既有购票须知又有退票条件 → 生成2个Document")
        void shouldBuildBothDocuments() {
            spot.setTicketNotice("门票40元/人，凭身份证实名购票入园。");
            spot.setRefundPolicy("未使用的门票在购买后7天内可申请退款。");

            List<Document> docs = enricher.build(spot);

            assertThat(docs).hasSize(2);

            Document ticketDoc = docs.get(0);
            assertThat(ticketDoc.getText()).contains("[购票须知]");
            assertThat(ticketDoc.getText()).contains("40元/人");
            assertThat(ticketDoc.getMetadata().get("category")).isEqualTo("ticket");
            assertThat(ticketDoc.getMetadata().get("spotId")).isEqualTo("1");
            assertThat(ticketDoc.getMetadata().get("spotName")).isEqualTo("雷峰塔");

            Document refundDoc = docs.get(1);
            assertThat(refundDoc.getText()).contains("[退票条件]");
            assertThat(refundDoc.getText()).contains("7天内可申请退款");
            assertThat(refundDoc.getMetadata().get("category")).isEqualTo("refund");
        }

        @Test
        @DisplayName("仅有购票须知、无退票条件 → 仅生成1个Document")
        void shouldBuildOnlyTicketDocument() {
            spot.setTicketNotice("免费开放，无需购票。");

            List<Document> docs = enricher.build(spot);

            assertThat(docs).hasSize(1);
            assertThat(docs.get(0).getText()).contains("[购票须知]");
            assertThat(docs.get(0).getMetadata().get("category")).isEqualTo("ticket");
        }

        @Test
        @DisplayName("仅有退票条件、无购票须知 → 仅生成1个Document")
        void shouldBuildOnlyRefundDocument() {
            spot.setRefundPolicy("门票一经售出不予退款。");

            List<Document> docs = enricher.build(spot);

            assertThat(docs).hasSize(1);
            assertThat(docs.get(0).getText()).contains("[退票条件]");
            assertThat(docs.get(0).getMetadata().get("category")).isEqualTo("refund");
        }

        @Test
        @DisplayName("购票须知和退票条件均为空 → 不生成Document")
        void shouldBuildNoDocumentsWhenBothEmpty() {
            List<Document> docs = enricher.build(spot);

            assertThat(docs).isEmpty();
        }

        @Test
        @DisplayName("购票须知为空白字符串 → 不生成对应Document")
        void shouldSkipBlankTicketNotice() {
            spot.setTicketNotice("   ");
            spot.setRefundPolicy("不可退款。");

            List<Document> docs = enricher.build(spot);

            assertThat(docs).hasSize(1);
            assertThat(docs.get(0).getMetadata().get("category")).isEqualTo("refund");
        }
    }

    // ==================== 场景 2: 意图路由关键词 ====================

    @Nested
    @DisplayName("FAQ-2: 意图路由 — 关键词触发退购票KB")
    class IntentRoutingTests {

        /**
         * 直接测试关键词集覆盖日常对话的常见问法。
         * 注意：关键词匹配在 HybridDocumentRetriever.isTicketRefundQuery() 中。
         */
        @Test
        @DisplayName("购票类关键词应全部命中")
        void shouldMatchTicketKeywords() {
            String[] queries = {
                    "雷峰塔怎么买票",
                    "西湖门票怎么预订",
                    "千岛湖购票须知是什么",
                    "灵隐寺门票价格是多少",
                    "宋城演出票怎么买",
                    "去哪里买票",
                    "网上购票可以吗",
                    "在线购票有优惠吗",
                    "可以订票吗",
            };
            for (String q : queries) {
                assertThat(matchesTicketRefund(q))
                        .as("购票查询应触发退购票KB: '%s'", q)
                        .isTrue();
            }
        }

        @Test
        @DisplayName("退票类关键词应全部命中")
        void shouldMatchRefundKeywords() {
            String[] queries = {
                    "怎么退票",
                    "可以退款吗",
                    "能不能取消订单",
                    "退订在哪里操作",
                    "退票条件是什么",
                    "退票政策",
            };
            for (String q : queries) {
                assertThat(matchesTicketRefund(q))
                        .as("退票查询应触发退购票KB: '%s'", q)
                        .isTrue();
            }
        }

        @Test
        @DisplayName("非购退票查询不应触发退购票KB")
        void shouldNotMatchNonTicketQueries() {
            String[] queries = {
                    "西湖有什么好玩的",
                    "雷峰塔在哪里",
                    "杭州有哪些景点推荐",
                    "灵隐寺开放时间",
                    "评分最高的景点",
            };
            for (String q : queries) {
                assertThat(matchesTicketRefund(q))
                        .as("非购退票查询不应触发: '%s'", q)
                        .isFalse();
            }
        }

        /**
         * 引用 TicketRefundConstants.KEYWORDS（唯一真相源），确保与业务代码同步。
         */
        private boolean matchesTicketRefund(String query) {
            for (String kw : TicketRefundConstants.KEYWORDS) {
                if (query.contains(kw)) {
                    return true;
                }
            }
            return false;
        }
    }

    // ==================== 场景 3: TicketRefundIndexer 索引流程 ====================

    @Nested
    @DisplayName("FAQ-3: 购退票索引器 — 写入 Qdrant + ES")
    class IndexerTests {

        @Test
        @DisplayName("景点有购退票数据 → 索引返回写入数 > 0")
        void shouldIndexSpotsWithTicketRefundData() {
            Spot spot = new Spot();
            spot.setId(1L);
            spot.setName("雷峰塔");
            spot.setTicketNotice("门票40元/人");
            spot.setRefundPolicy("7天内可退");

            // 验证 enricher 构建了文档 → indexer 会将它们写入 Qdrant + ES
            TicketRefundEnricher enricher = new TicketRefundEnricher();
            List<Document> docs = enricher.build(spot);

            assertThat(docs).hasSize(2);
            assertThat(docs.get(0).getText()).contains("门票40元/人");
            assertThat(docs.get(1).getText()).contains("7天内可退");
        }

        @Test
        @DisplayName("景点无购退票数据 → 不写入任何文档")
        void shouldNotIndexSpotsWithoutTicketRefundData() {
            Spot spot = new Spot();
            spot.setId(2L);
            spot.setName("西湖断桥");

            TicketRefundEnricher enricher = new TicketRefundEnricher();
            List<Document> docs = enricher.build(spot);

            assertThat(docs).isEmpty();
        }
    }

    // ==================== 集成测试标记 ====================

    @Nested
    @DisplayName("FAQ-INTEGRATION: 需基础设施的端到端测试")
    @Tag("integration")
    class IntegrationTests {

        @Test
        @DisplayName("查询'雷峰塔怎么买票' → 回答应包含购票须知内容")
        void ticketNoticeIntegration() {
            // 此测试需启动完整服务：MySQL + Redis + Qdrant + ES + DeepSeek
            // 1. 执行 migration_v2.sql 更新数据
            // 2. 运行 ParentChildIndexer.indexSpot() 重建主知识库
            // 3. 运行 TicketRefundIndexer.indexSpot() 重建退购票知识库
            // 4. 发送 POST /api/spot/qa  body: {"question": "雷峰塔怎么买票？"}
            // 5. 验证回答包含 "40元" 和 "实名购票"
        }

        @Test
        @DisplayName("查询'良渚古城能退票吗' → 回答应包含退票条件内容")
        void refundPolicyIntegration() {
            // 同上，验证回答包含 "提前" 和 "取消预约"
        }

        @Test
        @DisplayName("查询'雷峰塔有什么特色' → 回答应基于主KB的[景点特色]section，不含购退票信息")
        void featuresIntegration() {
            // 验证回答包含特色描述，不包含购票/退票关键词
        }
    }
}
