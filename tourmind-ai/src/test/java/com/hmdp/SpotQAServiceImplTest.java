package com.hmdp;

import com.hmdp.dto.Result;
import com.hmdp.dto.SpotDTO;
import com.hmdp.entity.Spot;
import com.hmdp.mapper.SpotMapper;
import com.hmdp.rag.retrieval.HybridDocumentRetriever;
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

    // ==================== answerSpotQuestion ====================

    @Nested
    @MockitoSettings(strictness = Strictness.LENIENT)
    @DisplayName("answerSpotQuestion — RAG 检索问答")
    class AnswerSpotQuestionTests {

        @BeforeEach
        void setUp() {
            initChatClientMock();
            initConversationMock();
        }

        @Test
        @DisplayName("正常问答 — 返回回答和推荐景点列表")
        void shouldReturnAnswerWithSpots() {
            // given
            Spot spot1 = buildSpot(1L, "川味观", "西湖区", 80L, 4);
            Spot spot2 = buildSpot(2L, "外婆家", "西湖区", 90L, 5);
            when(spotDocumentRetriever.getLastRetrievedSpots())
                    .thenReturn(List.of(spot1, spot2));

            // when
            Result result = service.answerSpotQuestion(USER_ID, null,
                    "推荐川菜馆", null, null, 5);

            // then
            assertThat(result.getSuccess()).isTrue();
            Map<String, Object> data = (Map<String, Object>) result.getData();
            assertThat(data.get("answer")).isEqualTo("这是 AI 的回答");
            assertThat(data.get("sessionId")).isEqualTo(CONVERSATION_ID);

            List<SpotDTO> spots = (List<SpotDTO>) data.get("recommendedSpots");
            assertThat(spots).hasSize(2);
            assertThat(spots.get(0).getName()).isEqualTo("川味观");
            assertThat(spots.get(1).getName()).isEqualTo("外婆家");

            // 验证坐标设置
            verify(spotDocumentRetriever).setUserCoordinates(null, null);
            // 验证 ThreadLocal 清理
            verify(spotDocumentRetriever).clearContext();
        }

        @Test
        @DisplayName("带用户坐标 — 调用 setUserCoordinates")
        void shouldSetUserCoordinates() {
            // given
            when(spotDocumentRetriever.getLastRetrievedSpots())
                    .thenReturn(Collections.emptyList());

            // when
            service.answerSpotQuestion(USER_ID, null,
                    "附近的咖啡店", 121.50, 31.23, 5);

            // then
            verify(spotDocumentRetriever).setUserCoordinates(121.50, 31.23);
            verify(spotDocumentRetriever).clearContext();
        }

        @Test
        @DisplayName("limit 截断 — 返回数量不超过 limit")
        void shouldLimitSpotsByParam() {
            // given
            Spot spot1 = buildSpot(1L, "A", "杭州", 10L, 1);
            Spot spot2 = buildSpot(2L, "B", "杭州", 20L, 2);
            Spot spot3 = buildSpot(3L, "C", "杭州", 30L, 3);
            when(spotDocumentRetriever.getLastRetrievedSpots())
                    .thenReturn(List.of(spot1, spot2, spot3));

            // when
            Result result = service.answerSpotQuestion(USER_ID, null,
                    "景点", null, null, 2);

            // then
            Map<String, Object> data = (Map<String, Object>) result.getData();
            List<SpotDTO> spots = (List<SpotDTO>) data.get("recommendedSpots");
            assertThat(spots).hasSize(2);
        }

        @Test
        @DisplayName("无匹配景点 — 返回空列表")
        void shouldReturnEmptySpotsWhenNoMatches() {
            // given
            when(spotDocumentRetriever.getLastRetrievedSpots())
                    .thenReturn(Collections.emptyList());

            // when
            Result result = service.answerSpotQuestion(USER_ID, null,
                    "不存在的查询", null, null, 5);

            // then
            assertThat(result.getSuccess()).isTrue();
            Map<String, Object> data = (Map<String, Object>) result.getData();
            List<SpotDTO> spots = (List<SpotDTO>) data.get("recommendedSpots");
            assertThat(spots).isEmpty();
        }

        @Test
        @DisplayName("空问题 — 返回失败")
        void shouldFailOnEmptyQuestion() {
            // when
            Result r1 = service.answerSpotQuestion(USER_ID, null, "", null, null, 5);
            Result r2 = service.answerSpotQuestion(USER_ID, null, null, null, null, 5);
            Result r3 = service.answerSpotQuestion(USER_ID, null, "   ", null, null, 5);

            // then
            assertThat(r1.getSuccess()).isFalse();
            assertThat(r2.getSuccess()).isFalse();
            assertThat(r3.getSuccess()).isFalse();
        }

        @Test
        @DisplayName("异常时 finally 仍执行 cleanup")
        void shouldCleanupOnException() {
            // given
            doThrow(new RuntimeException("模拟 ChatClient 异常"))
                    .when(requestSpec).call();

            // when
            try {
                service.answerSpotQuestion(USER_ID, null, "触发异常", null, null, 5);
            } catch (RuntimeException ignored) {
            }

            // then - finally 块仍执行了 clearContext
            verify(spotDocumentRetriever).clearContext();
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
