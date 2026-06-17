package com.hmdp.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 短期记忆配置 — 滑动窗口 + 摘要压缩触发检测。
 *
 * <h3>重构后架构</h3>
 * <p>实现了 Spring AI {@link ChatMemory} 接口的轻量滑动窗口，
 * 替代旧版的 {@code InMemoryChatMemory} 和 {@code PersistentChatMemory}。</p>
 *
 * <h3>职责分工</h3>
 * <ul>
 *   <li><b>短期记忆（本类）</b> — 滑动窗口维护最近 N 轮对话，窗口溢出触发摘要压缩</li>
 *   <li><b>长期记忆（MemoryCoordinator，Phase D）</b> — MySQL 结构化偏好 + Qdrant 非结构化知识</li>
 * </ul>
 *
 * <h3>参数</h3>
 * <ul>
 *   <li>maxMessages: 20 条（= 10 轮对话，每轮 User + Assistant）</li>
 *   <li>摘要触发阈值: 第 11 轮进入时触发</li>
 *   <li>无外部依赖: 纯 ConcurrentHashMap，不做持久化（持久化由长期记忆层负责）</li>
 * </ul>
 */
@Slf4j
@Configuration
public class ChatMemoryConfig {

    /** 滑动窗口大小（10 轮 = 20 条消息） */
    static final int MAX_MESSAGES = 20;

    /** 每 N 轮触发一次摘要压缩检查 */
    static final int SUMMARY_INTERVAL_ROUNDS = 10;

    @Bean
    public ChatMemory chatMemory() {
        log.info("短期记忆初始化: ShortTermMemory, maxMessages={}", MAX_MESSAGES);
        return new ShortTermMemory(MAX_MESSAGES);
    }

    // ==================== 短期记忆实现 ====================

    /**
     * 基于 {@link ConcurrentHashMap} + {@link ConcurrentLinkedDeque} 的轻量滑动窗口。
     */
    public static class ShortTermMemory implements ChatMemory {

        private final ConcurrentHashMap<String, ConcurrentLinkedDeque<Message>> store;
        private final int maxMessages;

        ShortTermMemory(int maxMessages) {
            this.store = new ConcurrentHashMap<>();
            this.maxMessages = maxMessages;
        }

        @Override
        public void add(String conversationId, Message message) {
            if (conversationId == null || conversationId.isEmpty() || message == null) return;
            ConcurrentLinkedDeque<Message> deque = store.computeIfAbsent(
                    conversationId, k -> new ConcurrentLinkedDeque<>());
            while (deque.size() >= maxMessages) {
                deque.pollFirst();  // FIFO 淘汰最旧消息
            }
            deque.offerLast(message);
        }

        @Override
        public void add(String conversationId, List<Message> messages) {
            if (conversationId == null || conversationId.isEmpty()
                    || messages == null || messages.isEmpty()) return;
            ConcurrentLinkedDeque<Message> deque = store.computeIfAbsent(
                    conversationId, k -> new ConcurrentLinkedDeque<>());
            for (Message msg : messages) {
                while (deque.size() >= maxMessages) {
                    deque.pollFirst();
                }
                deque.offerLast(msg);
            }
        }

        @Override
        public List<Message> get(String conversationId) {
            if (conversationId == null || conversationId.isEmpty()) return Collections.emptyList();
            Deque<Message> deque = store.get(conversationId);
            return (deque == null || deque.isEmpty())
                    ? Collections.emptyList()
                    : new ArrayList<>(deque);
        }

        @Override
        public void clear(String conversationId) {
            if (conversationId != null && !conversationId.isEmpty()) {
                store.remove(conversationId);
            }
        }
    }

    // ==================== 工具方法 ====================

    /** 统计会话的用户消息轮数 */
    public static int countRounds(ChatMemory memory, String conversationId) {
        List<Message> messages = memory.get(conversationId);
        return (int) messages.stream()
                .filter(m -> m.getMessageType() == MessageType.USER)
                .count();
    }

    /** 判断是否应该触发摘要压缩 */
    public static boolean shouldSummarize(ChatMemory memory, String conversationId) {
        int rounds = countRounds(memory, conversationId);
        return rounds > MAX_MESSAGES / 2 && rounds % SUMMARY_INTERVAL_ROUNDS == 0;
    }
}
