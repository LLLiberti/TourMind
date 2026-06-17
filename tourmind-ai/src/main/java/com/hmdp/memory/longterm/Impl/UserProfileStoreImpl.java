package com.hmdp.memory.longterm.Impl;

import com.hmdp.mapper.UserProfileMapper;
import com.hmdp.entity.memory.UserProfile;
import com.hmdp.memory.longterm.UserProfileStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 用户画像存储 MySQL 实现。
 *
 * <p>使用 INSERT ON DUPLICATE KEY UPDATE 保证幂等性。
 * 每次更新后刷新 last_extracted_at 时间戳。</p>
 */
@Slf4j
@Component
public class UserProfileStoreImpl implements UserProfileStore {

    @Resource
    private UserProfileMapper mapper;

    @Override
    public UserProfile getOrCreate(Long userId) {
        UserProfile profile = mapper.selectByUserId(userId);
        if (profile == null) {
            profile = UserProfile.builder()
                    .userId(userId)
                    .preferredSpotTypes("[]")
                    .preferredAreas("[]")
                    .travelStyle("{}")
                    .totalConversations(0)
                    .totalQuestions(0)
                    .build();
            try {
                mapper.insert(profile);
                log.debug("UserProfile 创建: userId={}", userId);
            } catch (Exception e) {
                // 并发插入时可能重复，重新读取
                profile = mapper.selectByUserId(userId);
                if (profile == null) {
                    log.warn("UserProfile 创建失败且无法恢复: userId={}", userId);
                    return UserProfile.builder().userId(userId).build();
                }
            }
        }
        return profile;
    }

    @Override
    public void update(Long userId, Map<String, Object> updates) {
        if (updates == null || updates.isEmpty()) return;

        UserProfile profile = getOrCreate(userId);
        boolean changed = false;

        // 仅更新非 null 的字段
        for (Map.Entry<String, Object> entry : updates.entrySet()) {
            Object value = entry.getValue();
            if (value == null) continue;
            changed = true;
            switch (entry.getKey()) {
                case "preferredSpotTypes" -> profile.setPreferredSpotTypes((String) value);
                case "preferredAreas" -> profile.setPreferredAreas((String) value);
                case "budgetLevel" -> profile.setBudgetLevel((String) value);
                case "travelStyle" -> profile.setTravelStyle((String) value);
                case "preferredTime" -> profile.setPreferredTime((String) value);
                case "minRating" -> profile.setMinRating((Double) value);
                case "frequentlyViewedSpots" -> profile.setFrequentlyViewedSpots((String) value);
            }
        }

        if (changed) {
            profile.setLastExtractedAt(LocalDateTime.now());
            profile.setLastActiveAt(LocalDateTime.now());
            mapper.updateByUserId(profile);
            log.debug("UserProfile 更新: userId={}, fields={}", userId, updates.keySet());
        }
    }

    @Override
    public void incrementConversationCount(Long userId) {
        try {
            mapper.insertOrIncrement(userId);
        } catch (Exception e) {
            log.debug("UserProfile 对话计数更新失败: userId={}, msg={}", userId, e.getMessage());
        }
    }
}
