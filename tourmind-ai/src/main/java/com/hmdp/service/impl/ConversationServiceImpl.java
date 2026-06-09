package com.hmdp.service.impl;

import com.hmdp.config.ConversationConfig;
import com.hmdp.dto.Result;
import com.hmdp.entity.Conversation;
import com.hmdp.service.IConversationService;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 会话管理服务实现（纯内存存储）
 *
 * 聊天记忆不持久化到服务端，仅存储在 JVM 堆内存中。
 * 30 分钟无交互自动清除，服务重启后所有会话重置。
 */
@Slf4j
@Service
public class ConversationServiceImpl implements IConversationService {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 会话元数据存储
     * key = "userId:sessionId", value = Conversation
     */
    private final ConcurrentHashMap<String, Conversation> conversations = new ConcurrentHashMap<>();

    /**
     * 用户 → 会话集合映射
     * key = userId, value = 该用户的 sessionId 集合
     */
    private final ConcurrentHashMap<Long, Set<String>> userSessions = new ConcurrentHashMap<>();

    private final ScheduledExecutorService cleaner;

    @Resource
    private ConversationConfig config;

    @Resource
    private ChatMemory chatMemory;

    public ConversationServiceImpl() {
        this.cleaner = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "conversation-cleaner");
            t.setDaemon(true);
            return t;
        });
    }

    // ==================== 定时清理 ====================

    @jakarta.annotation.PostConstruct
    public void startCleaner() {
        // 每 5 分钟扫描清理过期会话
        cleaner.scheduleWithFixedDelay(this::cleanupExpiredConversations, 5, 5, TimeUnit.MINUTES);
        log.info("会话清理任务已启动，间隔=5分钟，超时={}分钟，消息上限={}，用户会话上限={}",
                config.getTimeoutMinutes(), config.getMaxMessages(), config.getMaxConversationsPerUser());
    }

    @PreDestroy
    public void shutdown() {
        log.info("ConversationServiceImpl 正在关闭，清除 {} 个会话", conversations.size());
        conversations.clear();
        userSessions.clear();
        cleaner.shutdownNow();
    }

    /**
     * 定时清理过期/超限的会话
     */
    private void cleanupExpiredConversations() {
        int cleaned = 0;
        Iterator<Map.Entry<String, Conversation>> it = conversations.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Conversation> entry = it.next();
            Conversation conv = entry.getValue();

            boolean shouldRemove = false;
            if (!conv.getIsValid()) {
                shouldRemove = true;
            } else if (conv.isTimeout(config.getTimeoutMinutes())) {
                conv.setIsValid(false);
                conv.setStatus(Conversation.ConversationStatus.TIMEOUT);
                shouldRemove = true;
            } else if (conv.isMessageLimitExceeded(config.getMaxMessages())) {
                conv.setIsValid(false);
                conv.setStatus(Conversation.ConversationStatus.MESSAGE_LIMIT_EXCEEDED);
                shouldRemove = true;
            }

            if (shouldRemove) {
                String[] parts = entry.getKey().split(":");
                if (parts.length == 2) {
                    Long userId = Long.valueOf(parts[0]);
                    String sessionId = parts[1];
                    Set<String> sessions = userSessions.get(userId);
                    if (sessions != null) {
                        sessions.remove(sessionId);
                        if (sessions.isEmpty()) {
                            userSessions.remove(userId);
                        }
                    }
                }
                it.remove();
                cleaned++;
            }
        }
        if (cleaned > 0) {
            log.info("清理了 {} 个过期会话", cleaned);
        }
    }

    // ==================== 会话操作 ====================

    @Override
    public String getOrCreateConversation(Long userId, String sessionId) {
        if (userId == null) {
            throw new IllegalArgumentException("用户 ID 不能为空");
        }
        String conversationId;
        // 如果未提供 sessionId，生成新的，否则复用
        if (sessionId == null || sessionId.isEmpty()) {
            sessionId = UUID.randomUUID().toString().replace("-", "");
            conversationId = userId + ":" + sessionId;
        } else if (sessionId.contains(":")) {
            // 传入了完整的 "userId:uuid" 格式，校验 userId 前缀
            String[] parts = sessionId.split(":", 2);
            if (String.valueOf(userId).equals(parts[0])) {
                conversationId = sessionId;
                sessionId = parts[1];  // 剥离 userId 前缀，用于 userSessions 索引
            } else {
                // userId 不匹配 → 伪造/跨用户 sessionId，重置为新会话
                log.warn("sessionId 前缀与 userId 不匹配 (userId={}, sessionId={})，重置为新会话", userId, sessionId);
                sessionId = UUID.randomUUID().toString().replace("-", "");
                conversationId = userId + ":" + sessionId;
            }
        } else {
            // 传入了纯 sessionId（无 ":"），拼接 userId 前缀
            conversationId = userId + ":" + sessionId;
        }


        Conversation existing = conversations.get(conversationId);
        if (existing != null) {
            // 复用已有会话
            existing.updateLastInteractionTime();
            log.debug("复用已有会话: {}", conversationId);
        } else {
            // 创建新会话
            Conversation conv = Conversation.builder()
                    .conversationId(conversationId)
                    .userId(userId)
                    .createTime(LocalDateTime.now())
                    .lastInteractionTime(LocalDateTime.now())
                    .messageCount(0)
                    .isValid(true)
                    .status(Conversation.ConversationStatus.ACTIVE)
                    .build();

            conversations.put(conversationId, conv);
            userSessions.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet()).add(sessionId);

            // 检查并清理超限的会话
            cleanupExcessConversations(userId);

            log.info("创建新会话: {}", conversationId);
        }

        return conversationId;
    }

    @Override
    public boolean isConversationValid(String conversationId) {
        if (conversationId == null || conversationId.isEmpty()) {
            return false;
        }

        String[] parts = conversationId.split(":");
        if (parts.length != 2) {
            return false;
        }

        Conversation conversation = conversations.get(conversationId);
        if (conversation == null || !conversation.getIsValid()) {
            return false;
        }

        // 实时检查超时和消息数
        if (conversation.isTimeout(config.getTimeoutMinutes())) {
            invalidateConversation(conversationId, Conversation.ConversationStatus.TIMEOUT);
            return false;
        }
        if (conversation.isMessageLimitExceeded(config.getMaxMessages())) {
            invalidateConversation(conversationId, Conversation.ConversationStatus.MESSAGE_LIMIT_EXCEEDED);
            return false;
        }

        return true;
    }

    @Override
    public Conversation.ConversationStatus getConversationStatus(Long userId, String sessionId) {
        String conversationId = userId + ":" + sessionId;
        Conversation conversation = conversations.get(conversationId);
        if (conversation == null) {
            return null;
        }

        if (!conversation.getIsValid()) {
            return conversation.getStatus();
        }
        if (conversation.isTimeout(config.getTimeoutMinutes())) {
            return Conversation.ConversationStatus.TIMEOUT;
        }
        if (conversation.isMessageLimitExceeded(config.getMaxMessages())) {
            return Conversation.ConversationStatus.MESSAGE_LIMIT_EXCEEDED;
        }
        return Conversation.ConversationStatus.ACTIVE;
    }

    @Override
    public List<Conversation> getUserConversations(Long userId) {
        Set<String> sessions = userSessions.get(userId);
        if (sessions == null || sessions.isEmpty()) {
            return Collections.emptyList();
        }

        List<Conversation> result = new ArrayList<>();
        for (String sessionId : sessions) {
            String conversationId = userId + ":" + sessionId;
            Conversation conv = conversations.get(conversationId);
            if (conv != null) {
                result.add(conv);
            }
        }

        // 按最后交互时间降序
        result.sort((a, b) -> b.getLastInteractionTime().compareTo(a.getLastInteractionTime()));
        return result;
    }

    @Override
    public void clearConversation(Long userId, String sessionId) {
        String conversationId = userId + ":" + sessionId;
        conversations.remove(conversationId);

        Set<String> sessions = userSessions.get(userId);
        if (sessions != null) {
            sessions.remove(sessionId);
            if (sessions.isEmpty()) {
                userSessions.remove(userId);
            }
        }

        // 同步清除 ChatMemory 中的对话记录（供 MessageChatMemoryAdvisor 使用）
        chatMemory.clear(conversationId);

        log.info("清除会话: {}", conversationId);
    }

    @Override
    public void clearAllUserConversations(Long userId) {
        Set<String> sessions = userSessions.remove(userId);
        if (sessions != null) {
            for (String sessionId : sessions) {
                String conversationId = userId + ":" + sessionId;
                conversations.remove(conversationId);
                chatMemory.clear(conversationId);
            }
        }
        log.info("清除用户所有会话: userId={}", userId);
    }

    @Override
    public void incrementMessageCount(String conversationId) {
        Conversation conv = conversations.get(conversationId);
        if (conv != null) {
            conv.incrementMessageCount();
        }
    }

    @Override
    public Result getConversationInfo(Long userId, String sessionId) {
        String conversationId = userId + ":" + sessionId;
        Conversation conversation = conversations.get(conversationId);
        if (conversation == null) {
            return Result.fail("会话不存在");
        }

        Map<String, Object> info = new HashMap<>();
        info.put("conversationId", conversation.getConversationId());
        info.put("createTime", conversation.getCreateTime().format(TIME_FORMATTER));
        info.put("lastInteractionTime", conversation.getLastInteractionTime().format(TIME_FORMATTER));
        info.put("messageCount", conversation.getMessageCount());
        info.put("isValid", conversation.getIsValid());
        info.put("status", getConversationStatus(userId, sessionId));
        info.put("timeoutMinutes", config.getTimeoutMinutes());
        info.put("maxMessages", config.getMaxMessages());

        return Result.ok(info);
    }

    // ==================== 内部方法 ====================

    /**
     * 清理用户超限的会话（保留最近 N 个）
     */
    private void cleanupExcessConversations(Long userId) {
        Set<String> sessions = userSessions.get(userId);
        if (sessions == null || sessions.size() <= config.getMaxConversationsPerUser()) {
            return;
        }

        // 获取所有会话，按最后交互时间排序
        List<Map.Entry<String, LocalDateTime>> sorted = new ArrayList<>();
        for (String sessionId : sessions) {
            String convId = userId + ":" + sessionId;
            Conversation conv = conversations.get(convId);
            if (conv != null) {
                sorted.add(new AbstractMap.SimpleEntry<>(sessionId, conv.getLastInteractionTime()));
            }
        }

        sorted.sort((a, b) -> b.getValue().compareTo(a.getValue()));

        // 删除超出上限的会话
        for (int i = config.getMaxConversationsPerUser(); i < sorted.size(); i++) {
            String sessionId = sorted.get(i).getKey();
            String convId = userId + ":" + sessionId;
            conversations.remove(convId);
            sessions.remove(sessionId);
            log.info("自动清理超限会话: {}", convId);
        }
    }

    private void invalidateConversation(String conversationId, Conversation.ConversationStatus status) {
        Conversation conv = conversations.get(conversationId);
        if (conv != null) {
            conv.setIsValid(false);
            conv.setStatus(status);
        }
    }
}
