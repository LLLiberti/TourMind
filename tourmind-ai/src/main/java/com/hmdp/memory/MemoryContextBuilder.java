package com.hmdp.memory;

import com.hmdp.entity.memory.MemoryEntry;
import com.hmdp.entity.memory.SessionSummary;
import com.hmdp.entity.memory.UserProfile;

import java.util.List;

/**
 * 上下文组装器 — 将长期记忆组装为注入到 Agent SystemPrompt 的文本。
 *
 * <h3>输出格式（注入到 SystemPrompt 最前面）</h3>
 * <pre>
 * [用户画像]
 * - 偏好景点类型: 自然风光、历史古迹
 * - 预算水平: 中等
 *
 * [近期对话摘要]
 * - 话题：杭州亲子游景点推荐与门票对比
 *   摘要：用户从询问西湖开始，逐步聚焦到亲子景点...
 *   用户目标：寻找适合带孩子的景点，预算中等
 *   当前焦点：杭州动物园门票和优惠券
 *
 * [相关历史记忆]
 * - 用户偏好安静人少的景点
 * </pre>
 */
public class MemoryContextBuilder {

    private static final String SECTION_SEPARATOR = "\n";

    /**
     * 构建注入到 SystemPrompt 的长期记忆上下文。
     */
    public static String build(UserProfile profile,
                               List<SessionSummary> sessionSummaries,
                               List<MemoryEntry> semanticMemories) {
        StringBuilder sb = new StringBuilder();

        // Section 1: 用户画像
        if (profile != null && profile.hasPreferences()) {
            sb.append(profile.toContextText());
        }

        // Section 2: 近期会话摘要（MySQL session_summary）
        if (sessionSummaries != null && !sessionSummaries.isEmpty()) {
            if (!sb.isEmpty()) sb.append(SECTION_SEPARATOR);
            sb.append("【近期对话摘要】\n");
            for (SessionSummary s : sessionSummaries) {
                sb.append(s.toContextLine());
            }
        }

        // Section 3: 语义记忆（Qdrant）
        if (semanticMemories != null && !semanticMemories.isEmpty()) {
            if (!sb.isEmpty()) sb.append(SECTION_SEPARATOR);
            sb.append("【相关历史记忆】\n");
            for (int i = 0; i < semanticMemories.size(); i++) {
                MemoryEntry entry = semanticMemories.get(i);
                sb.append(entry.toContextLine());
                if (i < semanticMemories.size() - 1) sb.append("\n");
            }
        }

        return sb.toString().trim();
    }

    public static String buildFromProfile(UserProfile profile) {
        return build(profile, null, null);
    }

    public static boolean hasMemoryContext(UserProfile profile,
                                           List<SessionSummary> sessionSummaries,
                                           List<MemoryEntry> semanticMemories) {
        boolean hasProfile = profile != null && profile.hasPreferences();
        boolean hasSummaries = sessionSummaries != null && !sessionSummaries.isEmpty();
        boolean hasMemories = semanticMemories != null && !semanticMemories.isEmpty();
        return hasProfile || hasSummaries || hasMemories;
    }
}
