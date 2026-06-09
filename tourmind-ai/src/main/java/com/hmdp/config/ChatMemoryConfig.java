package com.hmdp.config;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 聊天记忆配置（纯内存存储，无服务端持久化）
 *
 * 设计原则：
 * - 聊天记忆仅存储在 JVM 堆内存中，服务重启后清空
 * - 消息数上限由 ConversationConfig.maxMessages 控制，滑动窗口自动淘汰
 * - 会话有效性校验（超时/消息数超限）由 ConversationService 负责，
 *   ChatMemory 只负责消息的存取
 */
@Slf4j
@Configuration
public class ChatMemoryConfig {

    @Bean
    public ChatMemory chatMemory(ConversationConfig config) {
        return new InMemoryChatMemory(config);
    }

    /**
     * 基于 ConcurrentHashMap 的纯内存聊天记忆实现
     */
    public static class InMemoryChatMemory implements ChatMemory {

        /**
         * 会话消息存储
         * key   = conversationId ("userId:sessionId")
         * value = 消息队列（ConcurrentLinkedDeque，线程安全双端队列）
         */
        private final ConcurrentHashMap<String, ConcurrentLinkedDeque<Message>> conversations;

        private final int maxMessages;
        private final ScheduledExecutorService cleaner;

        public InMemoryChatMemory(ConversationConfig config) {
            this.conversations = new ConcurrentHashMap<>();
            this.maxMessages = config.getMaxMessages();
            this.cleaner = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "chat-memory-cleaner");
                t.setDaemon(true);
                return t;
            });
            log.info("InMemoryChatMemory 初始化完成，消息上限={}", maxMessages);
        }

        @Override
        public void add(String conversationId, Message message) {
            if (conversationId == null || conversationId.isEmpty() || message == null) {
                return;
            }

            ConcurrentLinkedDeque<Message> deque = conversations.computeIfAbsent(
                    conversationId, k -> new ConcurrentLinkedDeque<>());

            // 滑动窗口：超出上限时移除最旧消息
            while (deque.size() >= maxMessages) {
                deque.pollFirst();
            }
            deque.offerLast(message);
        }

        @Override
        public void add(String conversationId, List<Message> messages) {
            if (conversationId == null || conversationId.isEmpty()
                    || messages == null || messages.isEmpty()) {
                return;
            }

            ConcurrentLinkedDeque<Message> deque = conversations.computeIfAbsent(
                    conversationId, k -> new ConcurrentLinkedDeque<>());

            for (Message message : messages) {
                while (deque.size() >= maxMessages) {
                    deque.pollFirst();
                }
                deque.offerLast(message);
            }
        }

        @Override
        public List<Message> get(String conversationId) {
            if (conversationId == null || conversationId.isEmpty()) {
                return Collections.emptyList();
            }

            Deque<Message> deque = conversations.get(conversationId);
            if (deque == null || deque.isEmpty()) {
                return Collections.emptyList();
            }
            return new ArrayList<>(deque);
        }

        @Override
        public void clear(String conversationId) {
            if (conversationId != null) {
                conversations.remove(conversationId);
            }
        }

        @PreDestroy
        public void shutdown() {
            log.info("InMemoryChatMemory 正在关闭，清除 {} 个会话", conversations.size());
            conversations.clear();
            cleaner.shutdownNow();
        }
    }
}
