package com.hmdp.entity.memory;

/**
 * 对话轮次记录 — 内存缓冲中的单轮对话。
 *
 * <p>不入库，仅用于 MemoryCoordinator 缓冲多轮对话后批量提取。
 * 每次 Agent 回答后追加到 ConcurrentHashMap 的 Session 缓冲列表中。</p>
 *
 * @param question      用户问题
 * @param answer        Agent 最终回答
 * @param toolCallsText 工具调用过程文本（Tool Calls + Observations 摘要）
 * @param timestamp     时间戳（毫秒）
 */
public record TurnRecord(
        String question,
        String answer,
        String toolCallsText,
        long timestamp
) {
    public TurnRecord {
        if (question == null) question = "";
        if (answer == null) answer = "";
        if (toolCallsText == null) toolCallsText = "";
    }
}
