package com.hmdp.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.agent.AgentStep;
import com.hmdp.entity.memory.ExtractedMemory;
import com.hmdp.entity.memory.MemoryEntry;
import com.hmdp.entity.memory.SessionSummary;
import com.hmdp.entity.memory.TurnRecord;
import com.hmdp.entity.memory.UserProfile;
import com.hmdp.mapper.SessionSummaryMapper;
import com.hmdp.memory.longterm.MemoryExtractor;
import com.hmdp.memory.longterm.UserKnowledgeMemoryStore;
import com.hmdp.memory.longterm.UserProfileStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 记忆协调器 — Pre-During-Post 三阶段记忆管理（v3.1 会话级 upsert）。
 *
 * <h3>核心变更（v3 → v3.1）</h3>
 * <ol>
 *   <li><b>session_summary 单行 upsert</b>：每个 Session 仅一行，随对话推进更新，
 *       而非每次批量提取新增一行</li>
 *   <li><b>增量合并</b>：提取时传入已有摘要，LLM 在基础上合并新信息</li>
 *   <li><b>摘要字段扩展</b>：topic / userGoal / currentFocus / unresolvedQuestions</li>
 * </ol>
 *
 * <h3>短期 vs 长期</h3>
 * <p>最近 10 轮由 ChatMemory 滑动窗口提供（在线），超出窗口的历史由
 * session_summary + Qdrant 语义记忆提供（离线）。</p>
 */
@Slf4j
@Component
public class MemoryCoordinator {

    /** 触发批量提取的缓冲轮数阈值 */
    static final int FLUSH_THRESHOLD = 10;

    /** 事实存储重要性阈值 */
    private static final int MIN_FACT_IMPORTANCE = 3;

    /** Session → 缓冲轮次列表 */
    private final ConcurrentHashMap<String, List<TurnRecord>> sessionBuffer = new ConcurrentHashMap<>();

    @Resource
    private UserProfileStore profileStore;

    @Resource
    private UserKnowledgeMemoryStore knowledgeStore;

    @Resource
    private MemoryExtractor memoryExtractor;

    @Resource
    private SessionSummaryMapper sessionSummaryMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ==================== Phase 1: Pre-Task — 读取记忆 ====================

    /**
     * 请求开始前读取长期记忆，构建注入到 Agent SystemPrompt 的上下文文本。
     *
     * <p>读取顺序：用户画像 → 当前会话摘要 → 历史会话摘要 → 语义记忆 → 组装。</p>
     */
    public String buildMemoryContext(Long userId, String currentQuery) {
        if (userId == null) return "";

        // 1. MySQL: 用户画像
        UserProfile profile = null;
        try {
            profile = profileStore.getOrCreate(userId);
        } catch (Exception e) {
            log.warn("MemoryCoordinator: 读取 UserProfile 失败, userId={}, msg={}", userId, e.getMessage());
        }

        // 2. MySQL: 近期会话摘要（跨 Session 对话历史）
        List<SessionSummary> summaries = Collections.emptyList();
        try {
            summaries = sessionSummaryMapper.selectRecentByUserId(userId, 5);
        } catch (Exception e) {
            log.warn("MemoryCoordinator: 读取 SessionSummary 失败, userId={}, msg={}", userId, e.getMessage());
        }

        // 3. Qdrant: 语义检索相关历史事实
        List<MemoryEntry> memories = Collections.emptyList();
        try {
            memories = knowledgeStore.search(userId, currentQuery, 5);
        } catch (Exception e) {
            log.warn("MemoryCoordinator: 语义检索失败, userId={}, msg={}", userId, e.getMessage());
        }

        // 4. 组装上下文
        String context = MemoryContextBuilder.build(profile, summaries, memories);
        if (!context.isEmpty()) {
            log.debug("MemoryCoordinator: Pre-Task 上下文构建完成, userId={}, len={}", userId, context.length());
        }
        return context;
    }

    // ==================== Phase 2: During-Task — 短期记忆 ====================
    // ChatMemory 滑动窗口自动管理

    // ==================== Phase 3: Post-Task — 缓冲 + 按需批量提取 ====================

    /**
     * Agent 回答后将本轮追加到 Session 缓冲。缓冲满阈值时触发异步批量提取。
     */
    @Async
    public CompletableFuture<Void> extractAndPersist(Long userId, String sessionId,
                                                      String question, String answer,
                                                      List<AgentStep> steps) {
        if (userId == null || sessionId == null) {
            return CompletableFuture.completedFuture(null);
        }

        try {
            TurnRecord turn = new TurnRecord(question, answer, buildToolCallsText(steps),
                    System.currentTimeMillis());

            List<TurnRecord> buffer = sessionBuffer.computeIfAbsent(
                    sessionId, k -> Collections.synchronizedList(new ArrayList<>()));

            buffer.add(turn);
            log.debug("MemoryCoordinator: 缓冲轮次, sessionId={}, size={}",
                    sessionId, buffer.size());

            if (buffer.size() >= FLUSH_THRESHOLD) {
                List<TurnRecord> toProcess;
                synchronized (buffer) {
                    toProcess = new ArrayList<>(buffer);
                    buffer.clear();
                }
                processBufferAsync(userId, sessionId, toProcess);
            }

        } catch (Exception e) {
            log.warn("MemoryCoordinator: 缓冲追加失败, userId={}, msg={}", userId, e.getMessage());
        }

        return CompletableFuture.completedFuture(null);
    }

    /**
     * Session 结束或显式清除时，将剩余缓冲（不足 10 轮）也提取。
     */
    public void flushSession(String sessionId, Long userId) {
        if (sessionId == null) return;

        List<TurnRecord> remaining = sessionBuffer.remove(sessionId);

        if (remaining == null || remaining.isEmpty()) {
            log.debug("MemoryCoordinator: flushSession 无剩余缓冲, sessionId={}", sessionId);
            return;
        }

        log.info("MemoryCoordinator: flushSession, sessionId={}, turns={}",
                sessionId, remaining.size());
        processBufferAsync(userId, sessionId, new ArrayList<>(remaining));
    }

    // ==================== 批量提取 + upsert 持久化 ====================

    private void processBufferAsync(Long userId, String sessionId,
                                     List<TurnRecord> turns) {
        if (turns.isEmpty()) return;

        log.info("MemoryCoordinator: 批量提取, sessionId={}, turns={}", sessionId, turns.size());

        try {
            // 1. 获取已有画像
            String existingProfile = "";
            try {
                UserProfile profile = profileStore.getOrCreate(userId);
                existingProfile = profile.toContextText();
            } catch (Exception e) {
                log.debug("MemoryCoordinator: 读取已有画像失败, userId={}", userId);
            }

            // 2. 获取已有会话摘要（用于增量合并）
            String existingSummaryText = "";
            SessionSummary existing = null;
            try {
                existing = sessionSummaryMapper.selectBySessionId(sessionId);
                if (existing != null && existing.hasContent()) {
                    existingSummaryText = existing.toContextLine();
                }
            } catch (Exception e) {
                log.debug("MemoryCoordinator: 读取已有摘要失败, sessionId={}", sessionId);
            }

            // 3. LLM 批量提取（带已有摘要的增量模式）
            ExtractedMemory extracted = memoryExtractor.extractBatch(
                    userId, turns, existingProfile, existingSummaryText);

            // 4. 会话摘要 → MySQL upsert
            if (extracted.hasConversationSummary()) {
                ExtractedMemory.ConversationSummaryResult cs = extracted.getConversationSummary();
                int totalRounds = (existing != null ? existing.getRoundCount() : 0) + turns.size();

                SessionSummary summary = SessionSummary.builder()
                        .sessionId(sessionId)
                        .userId(userId)
                        .topic(cs.getTopic())
                        .summary(cs.getSummary())
                        .userGoal(cs.getUserGoal())
                        .discussedEntities(toJsonSafe(cs.getDiscussedEntities()))
                        .currentFocus(cs.getCurrentFocus())
                        .unresolvedQuestions(toJsonSafe(cs.getUnresolvedQuestions()))
                        .roundCount(totalRounds)
                        .build();
                sessionSummaryMapper.upsert(summary);
                log.debug("MemoryCoordinator: 摘要 upsert, sessionId={}, rounds={}",
                        sessionId, totalRounds);
            }

            // 5. 用户画像 → MySQL upsert
            if (extracted.hasProfileUpdates()) {
                profileStore.update(userId, extracted.getProfileUpdates());
                log.debug("MemoryCoordinator: 画像更新, fields={}",
                        extracted.getProfileUpdates().keySet());
            }

            // 6. 知识事实 → Qdrant（重要性过滤 + 去重）
            if (extracted.hasNewFacts()) {
                int stored = 0, skippedLow = 0;
                for (ExtractedMemory.MemoryFact fact : extracted.getNewFacts()) {
                    if (fact.getImportance() < MIN_FACT_IMPORTANCE) {
                        skippedLow++;
                        continue;
                    }
                    knowledgeStore.store(userId, fact.getContent(),
                            fact.getType(), fact.getImportance());
                    stored++;
                }
                if (stored > 0 || skippedLow > 0) {
                    log.debug("MemoryCoordinator: facts stored={}, skipped(lowImportance)={}",
                            stored, skippedLow);
                }
            }

            // 7. 更新对话计数
            profileStore.incrementConversationCount(userId);

        } catch (Exception e) {
            log.warn("MemoryCoordinator: 批量提取失败, sessionId={}, msg={}",
                    sessionId, e.getMessage());
        }
    }

    // ==================== 内部工具方法 ====================

    private String buildToolCallsText(List<AgentStep> steps) {
        if (steps == null || steps.isEmpty()) return "";
        return steps.stream()
                .filter(s -> "TOOL_CALL".equals(s.getType()))
                .map(s -> String.format("[%s] %s", s.getToolName(), s.getContent()))
                .collect(Collectors.joining("\n"));
    }

    private String toJsonSafe(Object obj) {
        if (obj == null) return "[]";
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }
}
