package com.hmdp;

import com.hmdp.agent.AgentResult;
import com.hmdp.agent.AgentTrace;
import com.hmdp.agent.ReActAgentLoop;
import com.hmdp.dto.Result;
import com.hmdp.dto.SpotDTO;
import com.hmdp.entity.Spot;
import com.hmdp.mapper.SpotMapper;
import com.hmdp.rag.retrieval.HybridDocumentRetriever;
import com.hmdp.rag.router.QueryRouter;
import com.hmdp.service.IConversationService;
import com.hmdp.service.impl.SpotQAServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.chat.client.ChatClient;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * SpotQAServiceImpl 单元测试 — 验证问答逻辑
 *
 * <p>所有外部依赖（ChatClient、HybridDocumentRetriever、ConversationService）均 Mock，
 * 不需要 Spring 容器和外部基础设施。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SpotQAServiceImpl 单元测试")
class SpotQAServiceImplTest {

    @Mock
    private ChatClient chatClient;

    @Mock
    private SpotMapper spotMapper;

    @Mock
    private IConversationService conversationService;

    @Mock
    private HybridDocumentRetriever spotDocumentRetriever;

    @Mock
    private QueryRouter queryRouter;

    @Mock
    private ReActAgentLoop reActAgentLoop;

    @Mock
    private ChatClient.ChatClientRequestSpec requestSpec;

    @InjectMocks
    private SpotQAServiceImpl service;

    private static final Long USER_ID = 1001L;
    private static final String CONVERSATION_ID = "1001:test-session";

    /** 在需要 ChatClient 模拟的嵌套测试类中调用 */
    private void initChatClientMock() {
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.advisors(any(Consumer.class))).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        ChatClient.CallResponseSpec callResponse = mock(ChatClient.CallResponseSpec.class);
        when(requestSpec.call()).thenReturn(callResponse);
        when(callResponse.content()).thenReturn("这是 AI 的回答");
    }

    /** 在需要 ConversationService 模拟的嵌套测试类中调用 */
    private void initConversationMock() {
        when(conversationService.getOrCreateConversation(anyLong(), any()))
                .thenReturn(CONVERSATION_ID);
    }

    /** 默认：QueryRouter 返回 KNOWLEDGE（走 Agent 路径） */
    private void initQueryRouterMock() {
        when(queryRouter.classify(anyString())).thenReturn(QueryRouter.Category.KNOWLEDGE);
    }

    /** 设置 Agent 模式的默认 mock */
    private void initAgentMock() {
        AgentTrace trace = new AgentTrace();
        when(reActAgentLoop.thinkAndActWithPlan(anyString(), anyString(), any(), any()))
                .thenReturn(new AgentResult("这是 Agent 的回答", trace));
    }

    // ==================== answerSpotQuestion → 走 Agent 路径 ====================

    @Nested
    @MockitoSettings(strictness = Strictness.LENIENT)
    @DisplayName("answerSpotQuestion → 统一走 Agent 路径")
    class AnswerSpotQuestionTests {

        @BeforeEach
        void setUp() {
            initConversationMock();
            initQueryRouterMock();
            initAgentMock();
        }

        @Test
        @DisplayName("正常问答 — Agent 返回回答和推荐景点列表")
        void shouldReturnAnswerWithSpots() {
            Spot spot1 = buildSpot(1L, "川味观", "西湖区", 80L, 4);
            Spot spot2 = buildSpot(2L, "外婆家", "西湖区", 90L, 5);
            when(spotDocumentRetriever.getLastRetrievedSpots())
                    .thenReturn(List.of(spot1, spot2));

            Result result = service.answerSpotQuestion(USER_ID, null,
                    "推荐川菜馆", null, null, 5);

            assertThat(result.getSuccess()).isTrue();
            Map<String, Object> data = (Map<String, Object>) result.getData();
            assertThat(data.get("answer")).isEqualTo("这是 Agent 的回答");
            List<SpotDTO> spots = (List<SpotDTO>) data.get("recommendedSpots");
            assertThat(spots).hasSize(2);
            verify(spotDocumentRetriever).clearContext();
        }

        @Test
        @DisplayName("空问题 — 返回失败（Agent 未被调用）")
        void shouldFailOnEmptyQuestion() {
            Result r1 = service.answerSpotQuestion(USER_ID, null, "", null, null, 5);
            Result r2 = service.answerSpotQuestion(USER_ID, null, null, null, null, 5);
            assertThat(r1.getSuccess()).isFalse();
            assertThat(r2.getSuccess()).isFalse();
            verify(reActAgentLoop, never()).thinkAndActWithPlan(anyString(), anyString(), any(), any());
        }

        @Test
        @DisplayName("Agent 异常时 finally 仍执行 cleanup")
        void shouldCleanupOnException() {
            when(reActAgentLoop.thinkAndActWithPlan(anyString(), anyString(), any(), any()))
                    .thenThrow(new RuntimeException("Agent 异常"));

            try {
                service.answerSpotQuestion(USER_ID, null, "触发异常", null, null, 5);
            } catch (RuntimeException ignored) { }

            verify(spotDocumentRetriever).clearContext();
        }
    }

    // ==================== answerSpotQuestion — 闲聊分流 ====================

    @Nested
    @MockitoSettings(strictness = Strictness.LENIENT)
    @DisplayName("answerSpotQuestion — 闲聊分流（CHITCHAT）")
    class AnswerChitchatTests {

        @BeforeEach
        void setUp() {
            initChatClientMock();
            initConversationMock();
            // 覆盖为 CHITCHAT
            when(queryRouter.classify(anyString())).thenReturn(QueryRouter.Category.CHITCHAT);
        }

        @Test
        @DisplayName("闲聊问候 — 跳过 RAG 管线，返回 CHITCHAT 标记")
        void shouldSkipRagForChitchat() {
            Result result = service.answerSpotQuestion(USER_ID, "session-1",
                    "你好", null, null, 5);

            assertThat(result.getSuccess()).isTrue();
            Map<String, Object> data = (Map<String, Object>) result.getData();
            assertThat(data.get("category")).isEqualTo("CHITCHAT");
            assertThat(data.get("retrievalConfidence")).isEqualTo("SKIPPED");
            assertThat(data.get("answer")).isEqualTo("这是 AI 的回答");

            // 验证未调用 spotDocumentRetriever
            verify(spotDocumentRetriever, never()).setUserCoordinates(any(), any());
            verify(spotDocumentRetriever, never()).clearContext();
        }

        @Test
        @DisplayName("闲聊分流不触发检索 — getLastRetrievedSpots 未被调用")
        void shouldNotTouchRetrieverForChitchat() {
            service.answerSpotQuestion(USER_ID, null, "谢谢", null, null, 5);

            verify(spotDocumentRetriever, never()).getLastRetrievedSpots();
            verify(spotDocumentRetriever, never()).setUserCoordinates(any(), any());
        }
    }

    // ==================== answerSpotQuestion — CRAG 回退 ====================

    @Nested
    @MockitoSettings(strictness = Strictness.LENIENT)
    @DisplayName("answerSpotQuestion — CRAG 检索质量回退")
    class CRAGFallbackTests {

        @BeforeEach
        void setUp() {
            initConversationMock();
            initQueryRouterMock();
            initAgentMock();
        }

        @Test
        @DisplayName("INSUFFICIENT 置信度 — 回答前追加免责声明")
        void shouldAddDisclaimerWhenInsufficient() {
            when(spotDocumentRetriever.getLastRetrievalConfidence()).thenReturn("INSUFFICIENT");
            when(spotDocumentRetriever.getLastMaxRetrievalScore()).thenReturn(0.2);
            when(spotDocumentRetriever.getLastRetrievedSpots()).thenReturn(Collections.emptyList());

            Result result = service.answerSpotQuestion(USER_ID, null,
                    "根本不存在的奇怪问题xyz", null, null, 5);

            assertThat(result.getSuccess()).isTrue();
            Map<String, Object> data = (Map<String, Object>) result.getData();
            assertThat(data.get("retrievalConfidence")).isEqualTo("INSUFFICIENT");
            String answer = (String) data.get("answer");
            assertThat(answer).startsWith("⚠️");
            assertThat(answer).contains("以景区官方信息为准");
        }

        @Test
        @DisplayName("CONFIDENT 置信度 — 不追加免责声明")
        void shouldNotAddDisclaimerWhenConfident() {
            when(spotDocumentRetriever.getLastRetrievalConfidence()).thenReturn("CONFIDENT");
            when(spotDocumentRetriever.getLastRetrievedSpots()).thenReturn(Collections.emptyList());

            Result result = service.answerSpotQuestion(USER_ID, null,
                    "西湖有什么好玩的", null, null, 5);

            Map<String, Object> data = (Map<String, Object>) result.getData();
            assertThat(data.get("retrievalConfidence")).isEqualTo("CONFIDENT");
            String answer = (String) data.get("answer");
            assertThat(answer).doesNotStartWith("⚠️");
        }
    }

    // ==================== answerQuestionAboutSpot ====================

    @Nested
    @MockitoSettings(strictness = Strictness.LENIENT)
    @DisplayName("answerQuestionAboutSpot — 指定景点问答")
    class AnswerQuestionAboutSpotTests {

        @BeforeEach
        void setUp() {
            initChatClientMock();
            initConversationMock();
            initQueryRouterMock();
        }

        @Test
        @DisplayName("指定景点存在 — 返回回答和景点信息")
        void shouldAnswerForKnownSpot() {
            // given
            Spot spot = buildSpot(1L, "灵隐寺", "西湖区", 75L, 5);
            when(spotMapper.selectById(1L)).thenReturn(spot);

            // when
            Result result = service.answerQuestionAboutSpot(USER_ID, null,
                    1L, "灵隐寺怎么样？");

            // then
            assertThat(result.getSuccess()).isTrue();
            Map<String, Object> data = (Map<String, Object>) result.getData();
            SpotDTO spotDTO = (SpotDTO) data.get("spot");
            assertThat(spotDTO.getName()).isEqualTo("灵隐寺");
            assertThat(spotDTO.getTicketPrice()).isEqualTo(75L);
        }

        @Test
        @DisplayName("景点不存在 — 返回失败")
        void shouldFailOnNonExistentSpot() {
            // given
            when(spotMapper.selectById(999L)).thenReturn(null);

            // when
            Result result = service.answerQuestionAboutSpot(USER_ID, null,
                    999L, "不存在的景点");

            // then
            assertThat(result.getSuccess()).isFalse();
            assertThat(result.getErrorMsg()).contains("不存在");
        }

        @Test
        @DisplayName("空问题 — 返回失败")
        void shouldFailOnEmptyQuestionForKnownSpot() {
            // when
            Result result = service.answerQuestionAboutSpot(USER_ID, null, 1L, "");

            // then
            assertThat(result.getSuccess()).isFalse();
        }
    }

    // ==================== 会话管理 ====================

    @Nested
    @DisplayName("会话管理")
    class ConversationManagementTests {

        @Test
        @DisplayName("clearConversation — 委托 ConversationService")
        void shouldDelegateClearConversation() {
            service.clearConversation(USER_ID, "session-1");
            verify(conversationService).clearConversation(USER_ID, "session-1");
        }

        @Test
        @DisplayName("clearConversation — null 参数安全跳过")
        void shouldSkipClearOnNullParams() {
            service.clearConversation(null, "session-1");
            service.clearConversation(USER_ID, null);
            verify(conversationService, never()).clearConversation(anyLong(), anyString());
        }
    }

    // ==================== answerSpotQuestionAgent — Agent 模式 ====================

    @Nested
    @MockitoSettings(strictness = Strictness.LENIENT)
    @DisplayName("answerSpotQuestionAgent — Agent 模式问答")
    class AnswerSpotQuestionAgentTests {

        @BeforeEach
        void setUp() {
            initConversationMock();
            initQueryRouterMock();
            // 默认 Agent 返回
            AgentTrace trace = new AgentTrace();
            when(reActAgentLoop.thinkAndActWithPlan(anyString(), anyString(), any(), any()))
                    .thenReturn(new AgentResult("Agent 回答", trace));
        }

        @Test
        @DisplayName("正常 Agent 问答 — 返回回答、景点列表和 trace")
        void shouldReturnAnswerWithTrace() {
            Spot spot = buildSpot(1L, "西湖", "西湖区", 0L, 5);
            when(spotDocumentRetriever.getLastRetrievedSpots()).thenReturn(List.of(spot));
            when(spotDocumentRetriever.getLastRetrievalConfidence()).thenReturn("CONFIDENT");

            Result result = service.answerSpotQuestionAgent(USER_ID, null,
                    "西湖门票多少钱", null, null, 5);

            assertThat(result.getSuccess()).isTrue();
            Map<String, Object> data = (Map<String, Object>) result.getData();
            assertThat(data.get("answer")).isEqualTo("Agent 回答");
            assertThat(data.get("category")).isEqualTo("KNOWLEDGE");
            assertThat(data.get("agentTrace")).isNotNull();

            List<SpotDTO> spots = (List<SpotDTO>) data.get("recommendedSpots");
            assertThat(spots).hasSize(1);
            assertThat(spots.get(0).getName()).isEqualTo("西湖");

            verify(spotDocumentRetriever).clearContext();
        }

        @Test
        @DisplayName("闲聊分流 — 跳过 Agent，降级到 CHITCHAT")
        void shouldSkipAgentForChitchat() {
            when(queryRouter.classify(anyString())).thenReturn(QueryRouter.Category.CHITCHAT);
            initChatClientMock();

            Result result = service.answerSpotQuestionAgent(USER_ID, null,
                    "你好", null, null, 5);

            assertThat(result.getSuccess()).isTrue();
            Map<String, Object> data = (Map<String, Object>) result.getData();
            assertThat(data.get("category")).isEqualTo("CHITCHAT");
            assertThat(data.get("retrievalConfidence")).isEqualTo("SKIPPED");

            // Agent 未被调用
            verify(reActAgentLoop, never()).thinkAndActWithPlan(anyString(), anyString(), any(), any());
        }

        @Test
        @DisplayName("INSUFFICIENT 置信度 — 追加免责声明")
        void shouldAddDisclaimerWhenAgentInsufficient() {
            when(spotDocumentRetriever.getLastRetrievalConfidence()).thenReturn("INSUFFICIENT");
            when(spotDocumentRetriever.getLastRetrievedSpots()).thenReturn(Collections.emptyList());

            Result result = service.answerSpotQuestionAgent(USER_ID, null,
                    "不存在的景点", null, null, 5);

            String answer = (String) ((Map<String, Object>) result.getData()).get("answer");
            assertThat(answer).startsWith("⚠️");
        }

        @Test
        @DisplayName("空问题 — 返回失败")
        void shouldFailOnEmptyQuestion() {
            Result r1 = service.answerSpotQuestionAgent(USER_ID, null, "", null, null, 5);
            Result r2 = service.answerSpotQuestionAgent(USER_ID, null, null, null, null, 5);

            assertThat(r1.getSuccess()).isFalse();
            assertThat(r2.getSuccess()).isFalse();
            verify(reActAgentLoop, never()).thinkAndActWithPlan(anyString(), anyString(), any(), any());
        }
    }

    // ==================== 辅助方法 ====================

    private Spot buildSpot(Long id, String name, String area, Long ticketPrice, Integer score) {
        Spot spot = new Spot();
        spot.setId(id);
        spot.setName(name);
        spot.setArea(area);
        spot.setAddress(area + "某街道123号");
        spot.setTicketPrice(ticketPrice);
        spot.setScore(score);
        spot.setOpenHours("09:00-18:00");
        return spot;
    }
}
