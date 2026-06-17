package com.hmdp.memory.longterm;

import com.hmdp.entity.memory.MemoryEntry;

import java.util.List;

/**
 * 非结构化知识存储接口 — Qdrant 向量数据库。
 *
 * <p>存储粒度：每个独立知识点/事实/事件一条记录。
 * 检索方式：embedding 语义相似度 + userId 过滤。</p>
 */
public interface UserKnowledgeMemoryStore {

    /**
     * 存储一条记忆到 Qdrant。
     *
     * @param userId     用户 ID
     * @param content    记忆文本内容（会被 embedding）
     * @param memoryType 类型: fact / interaction / preference
     * @param importance 重要性 1-5
     * @return Qdrant document ID
     */
    String store(Long userId, String content, String memoryType, int importance);

    /**
     * 语义检索相关记忆。
     *
     * @param userId 用户 ID（过滤条件）
     * @param query  检索查询文本
     * @param topK   返回数量
     * @return 按相似度降序的记忆列表
     */
    List<MemoryEntry> search(Long userId, String query, int topK);

    /**
     * 删除指定记忆。
     */
    void delete(String memoryId);
}
