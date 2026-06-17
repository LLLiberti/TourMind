package com.hmdp.memory;

import com.hmdp.agent.AgentStep;
import com.hmdp.mapper.UserMemoryEventMapper;
import com.hmdp.memory.longterm.UserKnowledgeMemoryStore;
import com.hmdp.memory.longterm.MemoryExtractor;
import com.hmdp.memory.longterm.UserProfileStore;
import com.hmdp.entity.memory.ExtractedMemory;
import com.hmdp.entity.memory.MemoryEntry;
import com.hmdp.entity.memory.MemoryEvent;
import com.hmdp.entity.memory.UserProfile;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * 记忆协调器 — Pre-During-Post 三阶段记忆管理。
 *
 * <h3>职责</h3>
 * <ol>
 *   <li><b>Phase 1 (Pre-Task)</b> — 读取长期记忆，构建注入到 SystemPrompt 的上下文</li>
 *   <li><b>Phase 2 (During-Task)</b> — 短期记忆自动工作（ChatMemory 滑动窗口）</li>
 *   <li><b>Phase 3 (Post-Task)</b> — 异步提取并持久化本次对话的记忆</li>
 * </ol>
 *
 * <h3>降级</h3>
 * <p>MySQL 不可用时 profile 为 null（无画像注入）；Qdrant 不可用时 semanticMemories 为空；
 * MemoryExtractor 失败仅丢失本次记忆提取，不影响核心问答。</p>
 */
@Slf4j
@Component
public class MemoryCoordinator {

    @Resource
    private UserProfileStore profileStore;

    @Resource
    private UserKnowledgeMemoryStore knowledgeStore;

    @Resource
    private MemoryExtractor memoryExtractor;

    @Resource
    private UserMemoryEventMapper eventMapper;

    // ==================== Phase 1: Pre-Task — 读取记忆 ====================

    /**
     * 请求开始前读取长期记忆，构建注入到 Agent SystemPrompt 的上下文文本。
     *
     * @param userId       用户 ID
     * @param currentQuery 当前用户问题（用于语义检索）
     * @return 上下文文本（可能为空）
     */
    public String buildMemoryContext(Long userId, String currentQuery) {
        if (userId == null) return "";

        // 1. MySQL: 读取用户画像
        UserProfile profile = null;
        try {
            profile = profileStore.getOrCreate(userId);
        } catch (Exception e) {
            log.warn("MemoryCoordinator: 读取 UserProfile 失败, userId={}, msg={}", userId, e.getMessage());
        }

        // 2. Qdrant: 语义检索相关历史记忆
        List<MemoryEntry> memories = Collections.emptyList();
        try {
            memories = knowledgeStore.search(userId, currentQuery, 5);
        } catch (Exception e) {
            log.warn("MemoryCoordinator: 语义检索失败, userId={}, msg={}", userId, e.getMessage());
        }

        // 3. 组装上下文
        String context = MemoryContextBuilder.build(profile, memories);
        if (!context.isEmpty()) {
            log.debug("MemoryCoordinator: Pre-Task 上下文构建完成, userId={}, len={}", userId, context.length());
        }
        return context;
    }

    // ==================== Phase 2: During-Task ====================
    // 短期记忆（ChatMemory 滑动窗口）自动管理，协调器无需干预

    // ==================== Phase 3: Post-Task — 异步写入记忆 ====================

    /**
     * Agent 回答后异步提取并持久化长期记忆。
     *
     * <p>使用 Spring {@code @Async}，不阻塞 Agent 响应。
     * 失败仅记录日志，不影响核心问答功能。</p>
     *
     * @param userId   用户 ID
     * @param question 用户问题
     * @param answer   Agent 最终回答
     * @param steps    ReACT 步骤列表
     * @return 异步 Future
     */
    @Async
    public CompletableFuture<Void> extractAndPersist(Long userId, String question,
                                                      String answer, List<AgentStep> steps) {
        if (userId == null) return CompletableFuture.completedFuture(null);

        try {
            // 1. 构建工具调用过程文本
            String toolCallsText = buildToolCallsText(steps);

            // 2. 获取已有画像文本
            String existingProfile = "";
            try {
                UserProfile profile = profileStore.getOrCreate(userId);
                existingProfile = profile.toContextText();
            } catch (Exception e) {
                log.debug("MemoryCoordinator: 读取已有画像失败, userId={}", userId);
            }

            // 3. LLM 提取结构化记忆
            ExtractedMemory extracted = memoryExtractor.extract(
                    userId, question, answer, toolCallsText, existingProfile);

            // 4. 结构化偏好 → MySQL
            if (extracted.hasProfileUpdates()) {
                profileStore.update(userId, extracted.getProfileUpdates());
                // 记录事件
                saveEvent(userId, MemoryEvent.TYPE_PROFILE_UPDATE,
                        "用户画像字段更新: " + extracted.getProfileUpdates().keySet(),
                        extracted.getProfileUpdates().toString(), 3);
            }

            // 5. 非结构化知识 → Qdrant
            if (extracted.hasNewFacts()) {
                for (ExtractedMemory.MemoryFact fact : extracted.getNewFacts()) {
                    String memoryId = knowledgeStore.store(userId, fact.getContent(),
                            fact.getType(), fact.getImportance());
                    // 记录事件 + Qdrant 关联
                    saveEvent(userId, MemoryEvent.TYPE_FACT_LEARNED,
                            fact.getContent(), null, fact.getImportance());
                }
            }

            // 6. 交互摘要 → Qdrant
            if (extracted.getInteractionEvent() != null) {
                ExtractedMemory.InteractionEvent ie = extracted.getInteractionEvent();
                String qdrantId = knowledgeStore.store(userId, ie.getSummary(),
                        MemoryEntry.TYPE_INTERACTION, 3);
                saveEvent(userId, MemoryEvent.TYPE_INTERACTION_SUMMARY,
                        ie.getSummary(), null, 3);
            }

            // 7. 增加对话计数
            profileStore.incrementConversationCount(userId);

            log.debug("MemoryCoordinator: Post-Task 完成, userId={}, facts={}",
                    userId, extracted.getNewFacts().size());

        } catch (Exception e) {
            log.warn("MemoryCoordinator: Post-Task 失败, userId={}, msg={}", userId, e.getMessage());
        }

        return CompletableFuture.completedFuture(null);
    }

    // ==================== 内部方法 ====================

    private String buildToolCallsText(List<AgentStep> steps) {
        if (steps == null || steps.isEmpty()) return "";
        return steps.stream()
                .filter(s -> "TOOL_CALL".equals(s.getType()))
                .map(s -> String.format("[%s] %s", s.getToolName(), s.getContent()))
                .collect(Collectors.joining("\n"));
    }

    private void saveEvent(Long userId, String eventType, String summary,
                           String changeDetail, int importance) {
        try {
            MemoryEvent event = MemoryEvent.builder()
                    .userId(userId)
                    .eventType(eventType)
                    .summary(summary)
                    .changeDetail(changeDetail)
                    .importance(importance)
                    .build();
            eventMapper.insert(event);
        } catch (Exception e) {
            log.debug("MemoryCoordinator: 事件记录失败, type={}, msg={}", eventType, e.getMessage());
        }
    }
}
