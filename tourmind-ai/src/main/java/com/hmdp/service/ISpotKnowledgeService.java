package com.hmdp.service;

/**
 * 景点知识库服务 - 负责景点信息向量化
 */
public interface ISpotKnowledgeService {

    /**
     * 初始化景点知识库（将景点信息转为向量存入 Qdrant，并缓存到 Redis）
     * @param spotId 景点ID
     */
    void initializeSpotKnowledge(Long spotId);

    /**
     * 批量初始化所有景点知识库
     */
    void initializeAllSpotKnowledge();

    /**
     * 更新景点信息时同步更新向量
     * @param spotId 景点ID
     */
    void updateSpotKnowledge(Long spotId);

    /**
     * 删除景点知识库
     * @param spotId 景点ID
     */
    void deleteSpotKnowledge(Long spotId);
}
