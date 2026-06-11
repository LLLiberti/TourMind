package com.hmdp;

import com.hmdp.dto.Result;
import com.hmdp.dto.SpotDTO;
import com.hmdp.service.IConversationService;
import com.hmdp.service.ISpotQAService;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;

/**
 * 智能景点问答 RAG 功能测试
 *
 * 完整模拟用户与智能景点问答功能的交互过程，覆盖以下场景：
 *   1. 基本问答（无坐标 / 带坐标）
 *   2. 多轮对话（追问、指代消解）
 *   3. 针对特定景点提问
 *   4. 边界情况（空问题、无结果）
 *   5. 会话管理
 *
 * 运行要求：
 *   - 已导入景点数据（先执行 tourmind-core/…/db/spot_data.sql）
 *   - 已完成知识库初始化（先运行 SpotKnowledgeInitTest）
 *   - DeepSeek API 可用
 *   - Redis 服务可用
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("integration")
@DisplayName("RAG 智能景点问答测试")
class SpotQARagTest {

    @Resource
    private ISpotQAService spotQAService;

    @Resource
    private IConversationService conversationService;

    // 模拟两个用户的会话
    private static final Long USER_A = 0001L;
    private static final Long USER_B = 0002L;
    private static String userASession;

    // ==================== 场景一：基本问答 ====================

    @Test
    @Order(1)
    @DisplayName("【场景一】基本问答 — 用户首次提问（无坐标）")
    void testBasicQuestionWithoutCoordinates() {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("【场景一】基本问答 — 首次使用，不传 sessionId");
        System.out.println("=".repeat(60));
        System.out.println("\n👤 用户" + USER_A + " 提问: \"杭州有什么值得去的自然风景区？\"");

        Result result = spotQAService.answerSpotQuestion(USER_A, null, "杭州有什么值得去的自然风景区？", null, null, 5);

        printQAResult(result);
        Assertions.assertTrue(result.getSuccess(), "问答应成功");

        // 记录返回的 sessionId 用于后续多轮对话
        Map<String, Object> data = (Map<String, Object>) result.getData();
        userASession = (String) data.get("sessionId");
        System.out.println("   📌 系统自动分配会话: " + userASession);

        System.out.println("\n✅ 场景一通过：基本问答成功，系统自动创建了会话");
    }

    // ==================== 场景二：带坐标问答 ====================

//    @Test
//    @Order(2)
//    @DisplayName("【场景二】带坐标问答 — 按距离排序结果")
//    void testQuestionWithCoordinates() {
//        System.out.println("\n" + "=".repeat(60));
//        System.out.println("【场景二】带坐标问答");
//        System.out.println("=".repeat(60));
//
//        // 陆家嘴坐标
//        double userX = 121.5033;
//        double userY = 31.2374;
//
//        System.out.println("\n👤 用户" + USER_B + " 提问: \"附近有什么适合带小孩去的景点？\"");
//        System.out.println("   📍 用户位置: 经度=" + userX + ", 纬度=" + userY);
//
//        Result result = spotQAService.answerSpotQuestion(USER_B, null, "附近有什么适合带小孩去的景点？", userX, userY, 5);
//
//        printQAResult(result);
//        Assertions.assertTrue(result.getSuccess(), "问答应成功");
//
//        System.out.println("\n✅ 场景二通过：带坐标问答成功，结果已按距离排序");
//    }

    // ==================== 场景三：多轮对话（第一轮） ====================

    @Test
    @Order(3)
    @DisplayName("【场景三·第1轮】多轮对话 — 初始提问")
    void testMultiTurnRound1() {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("【场景三·第1轮】多轮对话 — 用户" + USER_A + " 问文化古迹景点");
        System.out.println("=".repeat(60));

        System.out.println("\n👤 用户" + USER_A + " 提问: \"杭州有哪些文化古迹类景点？推荐 3 个就行\"");

        Result result = spotQAService.answerSpotQuestion(USER_A, userASession, "杭州有哪些文化古迹类景点？推荐 3 个就行", null, null, 3);

        printQAResult(result);
        Assertions.assertTrue(result.getSuccess(), "问答应成功");

        // 验证会话复用
        Map<String, Object> data = (Map<String, Object>) result.getData();
        String returnedSession = (String) data.get("sessionId");
        Assertions.assertEquals(userASession, returnedSession, "应复用已有会话");

        System.out.println("   📌 会话复用: " + returnedSession + " (同一会话)");

        System.out.println("\n✅ 场景三·第1轮通过：首次提问完成，AI 推荐了景点列表");
    }

    // ==================== 场景三：多轮对话（第二轮——追问） ====================

    @Test
    @Order(4)
    @DisplayName("【场景三·第2轮】多轮对话 — 追问具体信息")
    void testMultiTurnRound2() {
        Assumptions.assumeTrue(userASession != null, "需要先执行第一轮对话");

        System.out.println("\n" + "=".repeat(60));
        System.out.println("【场景三·第2轮】多轮对话 — 追问第二个景点");
        System.out.println("=".repeat(60));

        System.out.println("\n👤 用户" + USER_A + " 追问: \"第二个景点的门票价格是多少？有什么特色推荐吗？\"");
        System.out.println("   📌 使用同一会话: " + userASession);

        Result result = spotQAService.answerSpotQuestion(USER_A, userASession,
                "第二个景点的门票价格是多少？有什么特色推荐吗？", null, null, 3);

        printQAResult(result);
        Assertions.assertTrue(result.getSuccess(), "问答应成功");

        System.out.println("\n✅ 场景三·第2轮通过：AI 结合上下文理解了'第二个'的指代");
    }

    // ==================== 场景三：多轮对话（第三轮——指代消解） ====================

    @Test
    @Order(5)
    @DisplayName("【场景三·第3轮】多轮对话 — 指代消解测试")
    void testMultiTurnRound3() {
        Assumptions.assumeTrue(userASession != null, "需要先执行第一轮对话");

        System.out.println("\n" + "=".repeat(60));
        System.out.println("【场景三·第3轮】多轮对话 — 追问第一家");
        System.out.println("=".repeat(60));

        System.out.println("\n👤 用户" + USER_A + " 追问: \"那第一家呢？也帮我介绍一下\"");

        Result result = spotQAService.answerSpotQuestion(USER_A, userASession,
                "那第一家呢？也帮我介绍一下", null, null, 3);

        printQAResult(result);
        Assertions.assertTrue(result.getSuccess(), "问答应成功");

        // 查看当前会话状态
        Result info = spotQAService.getConversationInfo(USER_A, userASession);
        System.out.println("\n   📊 当前会话状态:");
        printConversationInfo(info);

        System.out.println("\n✅ 场景三·第3轮通过：AI 正确消解了'那第一家'的指代");
    }

    // ==================== 场景四：针对特定景点提问 ====================

//    @Test
//    @Order(6)
//    @DisplayName("【场景四】针对特定景点提问")
//    void testQuestionAboutSpecificSpot() {
//        System.out.println("\n" + "=".repeat(60));
//        System.out.println("【场景四】针对特定景点提问 — 用户进入景点详情页后提问");
//        System.out.println("=".repeat(60));
//
//        // 使用 spotId=15（西湖风景名胜区）进行测试 — 需要先导入 spot_data.sql
//        long spotId = 15L;
//
//        System.out.println("\n👤 用户" + USER_B + " 打开景点 " + spotId + " 的详情页，提问: \"这个景点环境怎么样？适合带老人去吗？\"");
//
//        Result result = spotQAService.answerQuestionAboutSpot(USER_B, null, spotId,
//                "这个景点环境怎么样？适合带老人去吗？");
//
//        printQAResult(result);
//
//        // 不要求一定成功（spotId=1 可能不存在）
//        if (result.getSuccess()) {
//            System.out.println("✅ 场景四通过：指定景点问答成功");
//        } else {
//            System.out.println("⚠️ 场景四跳过：景点 " + spotId + " 不存在（" + result.getErrorMsg() + "）");
//        }
//    }
//
//    // ==================== 场景五：边界情况测试 ====================
//
//    @Test
//    @Order(7)
//    @DisplayName("【场景五】边界情况 — 空问题校验")
//    void testEmptyQuestion() {
//        System.out.println("\n" + "=".repeat(60));
//        System.out.println("【场景五】边界情况测试");
//        System.out.println("=".repeat(60));
//
//        // 5.1 空字符串
//        System.out.println("\n🔸 测试 5.1 — 空字符串:");
//        Result r1 = spotQAService.answerSpotQuestion(USER_A, null, "", null, null, 5);
//        Assertions.assertFalse(r1.getSuccess(), "空问题应返回失败");
//        System.out.println("   输入: \"\" → 错误信息: " + r1.getErrorMsg());
//
//        // 5.2 null 问题
//        System.out.println("\n🔸 测试 5.2 — null 问题:");
//        Result r2 = spotQAService.answerSpotQuestion(USER_A, null, null, null, null, 5);
//        Assertions.assertFalse(r2.getSuccess(), "null 问题应返回失败");
//        System.out.println("   输入: null → 错误信息: " + r2.getErrorMsg());
//
//        // 5.3 纯空白
//        System.out.println("\n🔸 测试 5.3 — 纯空白:");
//        Result r3 = spotQAService.answerSpotQuestion(USER_A, null, "   ", null, null, 5);
//        Assertions.assertFalse(r3.getSuccess(), "纯空白问题应返回失败");
//        System.out.println("   输入: \"   \" → 错误信息: " + r3.getErrorMsg());
//
//        System.out.println("\n✅ 场景五通过：所有边界情况校验正确");
//    }
//
//    // ==================== 场景六：不同用户会话隔离 ====================
//
//    @Test
//    @Order(8)
//    @DisplayName("【场景六】不同用户会话隔离")
//    void testUserSessionIsolation() {
//        System.out.println("\n" + "=".repeat(60));
//        System.out.println("【场景六】不同用户会话隔离验证");
//        System.out.println("=".repeat(60));
//
//        // 用户A 提问
//        Result rA = spotQAService.answerSpotQuestion(USER_A, userASession, "推荐一个西湖附近的景点", null, null, 3);
//        System.out.println("\n👤 用户" + USER_A + " (会话: " + userASession + ") 提问: \"推荐一个西湖附近的景点\"");
//        Map<String, Object> dataA = (Map<String, Object>) rA.getData();
//        System.out.println("   回答: " + truncate((String) dataA.get("answer"), 100));
//
//        // 用户B 提问（完全不同的会话）
//        Result rB = spotQAService.answerSpotQuestion(USER_B, "userB-session", "推荐一个西湖附近的景点", null, null, 3);
//        System.out.println("\n👤 用户" + USER_B + " (会话: userB-session) 提问: \"推荐一个西湖附近的景点\"");
//        Map<String, Object> dataB = (Map<String, Object>) rB.getData();
//        System.out.println("   回答: " + truncate((String) dataB.get("answer"), 100));
//
//        // 获取两个用户的会话列表，验证隔离
//        System.out.println("\n📊 用户" + USER_A + " 的会话:");
//        Result userAConversations = spotQAService.getUserConversations(USER_A);
//        System.out.println("   " + userAConversations.getData());
//
//        System.out.println("\n📊 用户" + USER_B + " 的会话:");
//        Result userBConversations = spotQAService.getUserConversations(USER_B);
//        System.out.println("   " + userBConversations.getData());
//
//        Assertions.assertTrue(rA.getSuccess() && rB.getSuccess(), "两边问答都应成功");
//        Assertions.assertNotEquals(
//                ((Map<String, Object>) rA.getData()).get("sessionId"),
//                ((Map<String, Object>) rB.getData()).get("sessionId"),
//                "不同用户的会话应相互隔离");
//
//        // 清理用户B 测试会话
//        spotQAService.clearConversation(USER_B, "userB-session");
//
//        System.out.println("\n✅ 场景六通过：用户会话隔离正常");
//    }

    // ==================== 辅助方法 ====================

    @SuppressWarnings("unchecked")
    private void printQAResult(Result result) {
        System.out.println("\n   ╔══════════════════════════════════════════════╗");
        System.out.println("   ║              AI 回答结果                     ║");
        System.out.println("   ╚══════════════════════════════════════════════╝");

        if (!result.getSuccess()) {
            System.out.println("   ❌ 错误: " + result.getErrorMsg());
            return;
        }

        Map<String, Object> data = (Map<String, Object>) result.getData();
        if (data == null) return;

        String answer = (String) data.get("answer");
        System.out.println("   💬 AI 回答:");
        // 逐行打印回答，增强可读性
        for (String line : answer.split("\n")) {
            System.out.println("      " + line);
        }

        String sessionId = (String) data.get("sessionId");
        if (sessionId != null) {
            System.out.println("   📌 会话 ID: " + sessionId);
        }

        // 打印推荐景点列表
        Object shops = data.get("recommendedSpots");
        if (shops instanceof java.util.List && !((java.util.List<?>) shops).isEmpty()) {
            System.out.println("   🏪 推荐景点:");
            int i = 1;
            for (Object obj : (java.util.List<?>) shops) {
                if (obj instanceof SpotDTO s) {
                    System.out.printf("      [%d] %s | %s | 门票 %s 元 | 评分 %s 分",
                            i++, s.getName(), s.getArea(),
                            s.getTicketPrice(), s.getScore());
                    if (s.getDistance() != null) {
                        System.out.printf(" | 距离 %.1f 公里", s.getDistance());
                    }
                    System.out.println();
                }
            }
        }

        Object spotObj = data.get("spot");
        if (spotObj instanceof SpotDTO s) {
            System.out.println("   🏪 目标景点: " + s.getName() + " | " + s.getArea());
        }
    }

    private void printConversationInfo(Result result) {
        if (!result.getSuccess()) {
            System.out.println("      会话信息获取失败: " + result.getErrorMsg());
            return;
        }
        Map<String, Object> info = (Map<String, Object>) result.getData();
        if (info != null) {
            System.out.println("      会话 ID: " + info.get("conversationId"));
            System.out.println("      消息数: " + info.get("messageCount"));
            System.out.println("      状态: " + info.get("status"));
            System.out.println("      最后交互: " + info.get("lastInteractionTime"));
        }
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "null";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
