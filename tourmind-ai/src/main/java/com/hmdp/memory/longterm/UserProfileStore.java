package com.hmdp.memory.longterm;

import com.hmdp.entity.memory.UserProfile;

import java.util.Map;

/**
 * 用户画像存储接口 — MySQL 结构化偏好持久化。
 *
 * <p>每用户一行。偏好字段在 MemoryExtractor 提取后增量更新。</p>
 */
public interface UserProfileStore {

    /**
     * 读取用户画像，不存在时创建空画像。
     */
    UserProfile getOrCreate(Long userId);

    /**
     * 增量更新用户画像字段。
     *
     * @param userId  用户 ID
     * @param updates 待更新的字段（key=字段名, value=新值），null 表示不变
     */
    void update(Long userId, Map<String, Object> updates);

    /**
     * 增加对话计数。
     */
    void incrementConversationCount(Long userId);
}
