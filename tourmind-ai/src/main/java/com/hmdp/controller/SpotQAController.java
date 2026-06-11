package com.hmdp.controller;

import com.hmdp.dto.Result;
import com.hmdp.service.ISpotKnowledgeService;
import com.hmdp.service.ISpotQAService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 智能商铺问答 Controller（支持多轮对话）
 */
@RestController
@RequestMapping("/ai/spot")
@RequiredArgsConstructor
public class SpotQAController {

    private final ISpotQAService spotQAService;
    private final ISpotKnowledgeService spotKnowledgeService;

    /**
     * 智能商铺问答（支持多轮对话）
     * POST /ai/spot/question
     */
    @PostMapping("/question")
    public Result askSpotQuestion(@RequestBody SpotQuestionRequest request) {
        if (request.getUserId() == null) {
            return Result.fail("用户 ID 不能为空");
        }
        
        return spotQAService.answerSpotQuestion(
                request.getUserId(),
                request.getSessionId(),
                request.getQuestion(),
                request.getUserX(),
                request.getUserY(),
                request.getLimit() != null ? request.getLimit() : 5
        );
    }

    /**
     * Agent 模式问答（LLM 自主决策检索/工具调用）
     * POST /ai/spot/question/agent
     */
    @PostMapping("/question/agent")
    public Result askSpotQuestionAgent(@RequestBody SpotQuestionRequest request) {
        if (request.getUserId() == null) {
            return Result.fail("用户 ID 不能为空");
        }
        return spotQAService.answerSpotQuestionAgent(
                request.getUserId(),
                request.getSessionId(),
                request.getQuestion(),
                request.getUserX(),
                request.getUserY(),
                request.getLimit() != null ? request.getLimit() : 5
        );
    }

    /**
     * 针对特定商铺提问（支持多轮对话）
     * POST /ai/spot/{spotId}/question
     */
    @PostMapping("/{spotId}/question")
    public Result askAboutSpot(
            @PathVariable Long spotId,
            @RequestBody SpotQuestionRequest request) {
        if (request.getUserId() == null) {
            return Result.fail("用户 ID 不能为空");
        }
        
        return spotQAService.answerQuestionAboutSpot(
                request.getUserId(),
                request.getSessionId(),
                spotId,
                request.getQuestion()
        );
    }

    /**
     * 清除会话历史
     * DELETE /ai/spot/conversation/{sessionId}
     */
    @DeleteMapping("/conversation/{sessionId}")
    public Result clearConversation(@PathVariable String sessionId, @RequestParam Long userId) {
        if (userId == null) {
            return Result.fail("用户 ID 不能为空");
        }
        spotQAService.clearConversation(userId, sessionId);
        return Result.ok("会话历史已清除");
    }

    /**
     * 获取会话信息
     * GET /ai/spot/conversation/{sessionId}/info
     */
    @GetMapping("/conversation/{sessionId}/info")
    public Result getConversationInfo(@PathVariable String sessionId, @RequestParam Long userId) {
        if (userId == null) {
            return Result.fail("用户 ID 不能为空");
        }
        return spotQAService.getConversationInfo(userId, sessionId);
    }

    /**
     * 获取用户所有会话
     * GET /ai/spot/conversations
     */
    @GetMapping("/conversations")
    public Result getUserConversations(@RequestParam Long userId) {
        if (userId == null) {
            return Result.fail("用户 ID 不能为空");
        }
        return spotQAService.getUserConversations(userId);
    }

    /**
     * 初始化商铺知识库（管理员接口）
     * POST /ai/spot/knowledge/init/{spotId}
     */
    @PostMapping("/knowledge/init/{spotId}")
    public Result initSpotKnowledge(@PathVariable Long spotId) {
        spotKnowledgeService.initializeSpotKnowledge(spotId);
        return Result.ok("知识库初始化完成");
    }

    /**
     * 初始化所有商铺知识库（管理员接口）
     * POST /ai/spot/knowledge/init-all
     */
    @PostMapping("/knowledge/init-all")
    public Result initAllSpotKnowledge() {
        spotKnowledgeService.initializeAllSpotKnowledge();
        return Result.ok("全部商铺知识库初始化完成");
    }

    @lombok.Data
    public static class SpotQuestionRequest {
        /**
         * 用户 ID（必填）
         */
        private Long userId;
        
        /**
         * 会话 ID（可选，用于多轮对话）
         * 如果不传，服务端会自动生成一个新的会话 ID
         */
        private String sessionId;
        
        /**
         * 用户问题
         */
        private String question;
        
        /**
         * 用户经度（可选）
         */
        private Double userX;
        
        /**
         * 用户纬度（可选）
         */
        private Double userY;
        
        /**
         * 返回数量限制（可选，默认 5）
         */
        private Integer limit;
    }
}
