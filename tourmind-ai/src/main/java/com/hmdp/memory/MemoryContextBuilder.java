package com.hmdp.memory;

import com.hmdp.entity.memory.MemoryEntry;
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
 * ...
 *
 * [相关历史记忆]
 * - 上次询问过杭州动物园，对动物主题景点感兴趣
 * - 用户偏好安静人少的景点
 * ...
 * </pre>
 */
public class MemoryContextBuilder {

    private static final String SECTION_SEPARATOR = "\n";

    /**
     * 构建注入到 SystemPrompt 的长期记忆上下文。
     *
     * @param profile          用户画像（可为 null）
     * @param semanticMemories 语义检索到的历史记忆
     * @return 上下文文本，无有效数据时返回空串
     */
    public static String build(UserProfile profile, List<MemoryEntry> semanticMemories) {
        StringBuilder sb = new StringBuilder();

        // Section 1: 用户画像
        if (profile != null && profile.hasPreferences()) {
            sb.append(profile.toContextText());
        }

        // Section 2: 语义记忆
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

    /**
     * 仅使用用户画像构建上下文（无语义记忆时使用）。
     */
    public static String buildFromProfile(UserProfile profile) {
        return build(profile, null);
    }

    /**
     * 判断是否有需要注入的内存上下文。
     */
    public static boolean hasMemoryContext(UserProfile profile, List<MemoryEntry> semanticMemories) {
        boolean hasProfile = profile != null && profile.hasPreferences();
        boolean hasMemories = semanticMemories != null && !semanticMemories.isEmpty();
        return hasProfile || hasMemories;
    }
}
