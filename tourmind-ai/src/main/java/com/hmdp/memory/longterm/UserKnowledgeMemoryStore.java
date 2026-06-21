package com.hmdp.memory.longterm;

import com.hmdp.entity.memory.MemoryEntry;

import java.util.List;

/**
 * 知识记忆存储接口 — 非结构化知识的向量存储与语义检索。
 *
 * <p>存储：将用户偏好/事实/交互事件转为向量存入 Qdrant。
 * 检索：按 userId 过滤 + 语义相似度搜索。</p>
 */
public interface UserKnowledgeMemoryStore {

    /**
     * 存储一条记忆。
     *
     * @param userId     用户 ID
     * @param content    记忆文本内容
     * @param memoryType 记忆类型 (fact / interaction / preference)
     * @param importance 重要性 1-5
     * @return Qdrant document ID
     */
    String store(Long userId, String content, String memoryType, int importance);

    /**
     * 语义检索用户记忆。
     *
     * @param userId 用户 ID
     * @param query  检索查询文本
     * @param topK   返回数量
     * @return 按相似度排序的记忆列表
     */
    List<MemoryEntry> search(Long userId, String query, int topK);

    /**
     * 删除指定记忆。
     *
     * @param memoryId Qdrant document ID
     */
    void delete(String memoryId);
}
