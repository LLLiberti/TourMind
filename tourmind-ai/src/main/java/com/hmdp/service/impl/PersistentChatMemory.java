package com.hmdp.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.*;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * 持久化聊天记忆 — Redis 热存储 + MySQL 摘要持久化。
 *
 * <h3>存储架构</h3>
 * <ul>
 *   <li><b>Redis</b> — 最近 N 轮完整 messages（JSON 序列化），TTL=24h</li>
 *   <li><b>摘要</b> — 每 5 轮触发一次增量摘要压缩，摘要存入 Redis（不设 TTL），
 *       供 Agent 系统 prompt 注入</li>
 * </ul>
 *
 * <h3>摘要格式</h3>
 * <pre>
 * "对话历史摘要：用户来自杭州，对自然风景区感兴趣，
 *  已查询过西湖（spotId=1）、雷峰塔（spotId=2），
 *  上一次询问了西湖门票价格，得到回复为 55 元。"
 * </pre>
 *
 * <h3>降级</h3>
 * <p>Redis 不可用时，内存中保留最近消息（类似原 InMemoryChatMemory），
 * 保证核心功能不中断。</p>
 */
@Slf4j
public class PersistentChatMemory implements ChatMemory {

    private static final String REDIS_KEY_PREFIX = "chat:memory:";
    private static final String REDIS_SUMMARY_KEY_PREFIX = "chat:summary:";
    private static final Duration REDIS_TTL = Duration.ofHours(24);

    /** 每 N 轮触发一次摘要压缩 */
    private static final int SUMMARY_INTERVAL = 5;
    /** 在 system prompt 中注入最近 N 轮完整 messages */
    private static final int RECENT_ROUNDS = 3;

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private final int maxMessages;
    private final int maxConversationsPerUser;

    /** 内存降级存储（Redis 不可用时） */
    private final Map<String, List<Message>> fallbackMemory = new LinkedHashMap<>();

    public PersistentChatMemory(RedisTemplate<String, String> redisTemplate,
                                 int maxMessages, int maxConversationsPerUser) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = new ObjectMapper();
        this.maxMessages = maxMessages;
        this.maxConversationsPerUser = maxConversationsPerUser;
        log.info("PersistentChatMemory 初始化: maxMessages={}, redisTtl={}", maxMessages, REDIS_TTL);
    }

    // ==================== ChatMemory 接口 ====================

    @Override
    public void add(String conversationId, Message message) {
        if (conversationId == null || message == null) return;
        add(conversationId, List.of(message));
    }

    @Override
    public void add(String conversationId, List<Message> messages) {
        if (conversationId == null || messages == null || messages.isEmpty()) return;

        List<Message> existing = get(conversationId);
        List<Message> all = new ArrayList<>(existing);
        all.addAll(messages);

        // 滑动窗口
        while (all.size() > maxMessages) {
            all.remove(0);
        }

        // 写入 Redis
        try {
            String json = serializeMessages(all);
            redisTemplate.opsForValue().set(REDIS_KEY_PREFIX + conversationId, json, REDIS_TTL);
        } catch (Exception e) {
            log.warn("Redis 写入聊天记忆失败，降级内存: {}", e.getMessage());
            fallbackMemory.put(conversationId, all);
        }
    }

    @Override
    public List<Message> get(String conversationId) {
        if (conversationId == null) return Collections.emptyList();

        // 从 Redis 读取
        try {
            String json = redisTemplate.opsForValue().get(REDIS_KEY_PREFIX + conversationId);
            if (json != null && !json.isEmpty()) {
                return deserializeMessages(json);
            }
        } catch (Exception e) {
            log.warn("Redis 读取聊天记忆失败，降级内存: {}", e.getMessage());
        }

        // 内存降级
        List<Message> fallback = fallbackMemory.get(conversationId);
        return fallback != null ? new ArrayList<>(fallback) : Collections.emptyList();
    }

    @Override
    public void clear(String conversationId) {
        if (conversationId == null) return;
        try {
            redisTemplate.delete(REDIS_KEY_PREFIX + conversationId);
            redisTemplate.delete(REDIS_SUMMARY_KEY_PREFIX + conversationId);
        } catch (Exception e) {
            log.warn("Redis 清除聊天记忆失败: {}", e.getMessage());
        }
        fallbackMemory.remove(conversationId);
    }

    // ==================== 摘要管理 ====================

    /**
     * 获取对话摘要（供 Agent system prompt 注入）。
     */
    public String getSummary(String conversationId) {
        if (conversationId == null) return "";
        try {
            String summary = redisTemplate.opsForValue().get(REDIS_SUMMARY_KEY_PREFIX + conversationId);
            return summary != null ? summary : "";
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 更新对话摘要（由外部 LLM 生成后调用）。
     */
    public void updateSummary(String conversationId, String summary) {
        if (conversationId == null || summary == null || summary.isBlank()) return;
        try {
            // 永久存储（或超长 TTL）
            redisTemplate.opsForValue().set(
                    REDIS_SUMMARY_KEY_PREFIX + conversationId, summary,
                    Duration.ofDays(30));
            log.debug("摘要已更新: conversationId={}, len={}", conversationId, summary.length());
        } catch (Exception e) {
            log.warn("Redis 写入摘要失败: {}", e.getMessage());
        }

    }

    /**
     * 检查是否应该触发摘要压缩。
     */
    public boolean shouldSummarize(String conversationId) {
        List<Message> messages = get(conversationId);
        // 每 SUMMARY_INTERVAL 轮触发一次
        int roundCount = (int) messages.stream()
                .filter(m -> m.getMessageType() == MessageType.USER)
                .count();
        return roundCount > 0 && roundCount % SUMMARY_INTERVAL == 0;
    }

    /**
     * 获取最近 N 轮完整对话（用于 Agent context），超出部分返回摘要。
     */
    public String buildContextForAgent(String conversationId) {
        List<Message> messages = get(conversationId);
        String summary = getSummary(conversationId);

        StringBuilder ctx = new StringBuilder();
        if (!summary.isEmpty()) {
            ctx.append("【对话历史摘要】").append(summary).append("\n\n");
        }

        // 最近 RECENT_ROUNDS 轮用户-助手对
        int recentPairs = RECENT_ROUNDS;
        List<Message> recent = new ArrayList<>();
        int userCount = 0;
        for (int i = messages.size() - 1; i >= 0 && userCount < recentPairs; i--) {
            Message msg = messages.get(i);
            if (msg.getMessageType() == MessageType.USER) {
                userCount++;
            }
            recent.add(0, msg);
        }

        if (!recent.isEmpty()) {
            ctx.append("【最近对话】\n");
            for (Message msg : recent) {
                String role = msg.getMessageType() == MessageType.USER ? "用户" : "助手";
                ctx.append(role).append("：").append(msg.getText()).append("\n");
            }
        }

        return ctx.toString();
    }

    /** 用户消息计数（用于摘要触发判断） */
    public int getUserMessageCount(String conversationId) {
        return (int) get(conversationId).stream()
                .filter(m -> m.getMessageType() == MessageType.USER)
                .count();
    }

    // ==================== 序列化 ====================

    private String serializeMessages(List<Message> messages) throws JsonProcessingException {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Message msg : messages) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("type", msg.getMessageType().name());
            map.put("text", msg.getText());
            list.add(map);
        }
        return objectMapper.writeValueAsString(list);
    }

    private List<Message> deserializeMessages(String json) throws JsonProcessingException {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = objectMapper.readValue(json, List.class);
        List<Message> messages = new ArrayList<>();
        for (Map<String, Object> map : list) {
            String type = (String) map.get("type");
            String text = (String) map.getOrDefault("text", "");
            switch (type) {
                case "USER" -> messages.add(new UserMessage(text));
                case "ASSISTANT" -> messages.add(new AssistantMessage(text));
                default -> {} // 跳过 SYSTEM 等
            }
        }
        return messages;
    }
}
