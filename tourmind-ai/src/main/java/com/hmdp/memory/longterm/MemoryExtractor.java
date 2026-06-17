package com.hmdp.memory.longterm;

import com.hmdp.entity.memory.ExtractedMemory;

/**
 * 记忆提取器接口 — 用 LLM 从对话中提取结构化记忆。
 *
 * <p>参考 Mem0 的 post-hoc extraction 模式：
 * 在 Agent 返回答案后异步调用，不阻塞用户体验。</p>
 */
public interface MemoryExtractor {

    /**
     * 从一轮对话中提取结构化记忆。
     *
     * @param userId     用户 ID
     * @param question   用户问题
     * @param answer     Agent 最终回答
     * @param toolCallsText  工具调用过程文本（Tool Calls + Observations 摘要）
     * @param existingProfile 已有用户画像（用于增量更新判断）
     * @return 提取的结构化记忆
     */
    ExtractedMemory extract(Long userId, String question, String answer,
                            String toolCallsText, String existingProfile);
}
