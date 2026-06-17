package com.hmdp.entity.memory;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Qdrant 中的记忆条目 — 非结构化知识/事件的存储单元。
 *
 * <p>每条 MemoryEntry 对应 Qdrant 中的一个向量文档，
 * embedding 由 content 文本经 bge-m3 生成。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemoryEntry {

    /** Qdrant document ID */
    private String id;

    /** 用户 ID（检索时的必需过滤条件） */
    private Long userId;

    /** 记忆类型: fact / interaction / preference */
    private String memoryType;

    /** 记忆文本内容（embedding 输入） */
    private String content;

    /** 重要性 1-5（用于检索排序权重） */
    private int importance;

    /** 来源会话 ID */
    private String sourceConversationId;

    /** Unix 时间戳（秒） */
    private long timestamp;

    // ==================== 类型常量 ====================

    public static final String TYPE_FACT = "fact";
    public static final String TYPE_INTERACTION = "interaction";
    public static final String TYPE_PREFERENCE = "preference";

    /**
     * 构建注入到 LLM 系统提示的文本。
     */
    public String toContextLine() {
        return "- " + content;
    }
}
