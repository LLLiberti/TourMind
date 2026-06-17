package com.hmdp.mapper;

import com.hmdp.entity.memory.UserProfile;
import org.apache.ibatis.annotations.*;

/**
 * 用户画像 MyBatis Mapper — MySQL user_profile 表。
 */
@Mapper
public interface UserProfileMapper {

    @Select("SELECT id, user_id, preferred_spot_types, preferred_areas, budget_level, " +
            "travel_style, preferred_time, min_rating, frequently_viewed_spots, " +
            "total_conversations, total_questions, last_extracted_at, last_active_at, " +
            "created_at, updated_at " +
            "FROM user_profile WHERE user_id = #{userId}")
    UserProfile selectByUserId(@Param("userId") Long userId);

    @Insert("INSERT INTO user_profile (user_id, preferred_spot_types, preferred_areas, " +
            "budget_level, travel_style, preferred_time, min_rating, frequently_viewed_spots, " +
            "total_conversations, total_questions, last_extracted_at, last_active_at) " +
            "VALUES (#{userId}, #{preferredSpotTypes}, #{preferredAreas}, " +
            "#{budgetLevel}, #{travelStyle}, #{preferredTime}, #{minRating}, #{frequentlyViewedSpots}, " +
            "#{totalConversations}, #{totalQuestions}, #{lastExtractedAt}, #{lastActiveAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(UserProfile profile);

    @Update("UPDATE user_profile SET " +
            "preferred_spot_types = #{preferredSpotTypes}, " +
            "preferred_areas = #{preferredAreas}, " +
            "budget_level = #{budgetLevel}, " +
            "travel_style = #{travelStyle}, " +
            "preferred_time = #{preferredTime}, " +
            "min_rating = #{minRating}, " +
            "frequently_viewed_spots = #{frequentlyViewedSpots}, " +
            "total_conversations = #{totalConversations}, " +
            "total_questions = #{totalQuestions}, " +
            "last_extracted_at = #{lastExtractedAt}, " +
            "last_active_at = #{lastActiveAt}, " +
            "updated_at = NOW() " +
            "WHERE user_id = #{userId}")
    int updateByUserId(UserProfile profile);

    @Insert("INSERT INTO user_profile (user_id, preferred_spot_types, preferred_areas, " +
            "budget_level, travel_style, preferred_time, min_rating, frequently_viewed_spots, " +
            "total_conversations, total_questions) " +
            "VALUES (#{userId}, '[]', '[]', NULL, '{}', NULL, NULL, '[]', 1, 1) " +
            "ON DUPLICATE KEY UPDATE total_conversations = total_conversations + 1")
    int insertOrIncrement(Long userId);
}
