package com.hmdp.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

/**
 * 对话摘要压缩服务 — 将对话历史压缩为结构化摘要。
 *
 * <h3>触发时机</h3>
 * <p>每 5 轮对话触发一次增量摘要压缩。
 * 初次摘要基于前 5 轮生成；后续每 5 轮做增量更新。</p>
 *
 * <h3>摘要内容</h3>
 * <ul>
 *   <li>用户画像：来自哪里、偏好类型</li>
 *   <li>已讨论实体：查询过的景点（ID+名称）、价格、优惠券</li>
 *   <li>已获取信息：哪些信息已经提供过（避免重复）</li>
 *   <li>未完成意图：用户还有哪些意图未完全满足</li>
 * </ul>
 */
@Slf4j
public class ConversationSummaryService {

    private static final String SUMMARY_PROMPT = """
        将以下对话历史压缩为一段简洁的结构化摘要。摘要应包含：
        1. 用户关注哪些地点/景点（含spotId）
        2. 用户偏好什么类型的景点
        3. 已提供过哪些关键信息（价格、天气、特色等）
        4. 用户是否有未完全满足的意图

        摘要控制在 80-150 字，使用中文。

        对话历史：
        %s

        已有摘要（如果是增量更新）：
        %s

        新摘要：""";

    private final ChatModel chatModel;

    public ConversationSummaryService(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    /**
     * 生成对话摘要（增量模式）。
     *
     * @param messages      最近 N 轮对话消息
     * @param existingSummary 已有的累积摘要（首次为空）
     * @return 更新后的摘要
     */
    public String summarize(List<Message> messages, String existingSummary) {
        if (messages == null || messages.isEmpty()) {
            return existingSummary != null ? existingSummary : "";
        }

        // 构建对话历史文本
        StringBuilder history = new StringBuilder();
        for (Message msg : messages) {
            String role = switch (msg.getMessageType()) {
                case USER -> "用户";
                case ASSISTANT -> "助手";
                default -> null;
            };
            if (role != null) {
                history.append(role).append("：").append(msg.getText()).append("\n");
            }
        }

        String promptText = String.format(SUMMARY_PROMPT, history,
                existingSummary != null && !existingSummary.isEmpty()
                        ? existingSummary : "（首次生成摘要）");

        try {
            Prompt prompt = new Prompt(List.of(
                    new SystemMessage("你是一个对话摘要专家。只输出摘要文本，不要任何解释。"),
                    new UserMessage(promptText)));
            ChatResponse response = chatModel.call(prompt);
            String summary = response.getResult().getOutput().getText();
            if (summary != null) {
                summary = summary.trim();
                log.debug("摘要生成: {} chars", summary.length());
                return summary;
            }
        } catch (Exception e) {
            log.warn("摘要生成失败: {}", e.getMessage());
        }

        return existingSummary != null ? existingSummary : "";
    }

    /**
     * 全文摘要（非增量，从零生成）。
     */
    public String summarize(List<Message> messages) {
        return summarize(messages, "");
    }
}
