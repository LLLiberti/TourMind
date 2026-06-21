package com.hmdp.memory.longterm;

import com.hmdp.entity.memory.ExtractedMemory;
import com.hmdp.entity.memory.TurnRecord;

import java.util.List;

/**
 * 记忆提取器接口 — 用 LLM 从多轮对话中批量提取结构化记忆（v3.1 会话级 upsert）。
 *
 * <h3>v3.1 变更</h3>
 * <p>摘要改为会话级单行 upsert：
 * <ul>
 *   <li>对话摘要 → MySQL session_summary（ON DUPLICATE KEY UPDATE）</li>
 *   <li>知识事实 → Qdrant（去重）</li>
 *   <li>用户画像更新 → MySQL user_profile（upsert）</li>
 * </ul>
 * 提取由 MemoryCoordinator 在满足触发条件（≥10轮 / Session结束）时异步调用。</p>
 */
public interface MemoryExtractor {

    /**
     * 从多轮对话中批量提取结构化记忆（首次提取，无已有摘要）。
     *
     * @param userId          用户 ID
     * @param turns           缓冲的多轮对话记录
     * @param existingProfile 已有用户画像文本（用于增量更新判断）
     * @return 提取的结构化记忆（画像更新 + 事实 + 会话摘要）
     */
    ExtractedMemory extractBatch(Long userId, List<TurnRecord> turns,
                                  String existingProfile);

    /**
     * 从多轮对话中批量提取结构化记忆（增量模式，带已有摘要作为合并基础）。
     *
     * @param userId           用户 ID
     * @param turns            缓冲的多轮对话记录
     * @param existingProfile  已有用户画像文本
     * @param existingSummary  已有会话摘要文本（用于增量合并）
     * @return 提取的结构化记忆
     */
    ExtractedMemory extractBatch(Long userId, List<TurnRecord> turns,
                                  String existingProfile, String existingSummary);
}
