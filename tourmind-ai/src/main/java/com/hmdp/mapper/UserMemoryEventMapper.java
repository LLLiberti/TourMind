package com.hmdp.mapper;

import com.hmdp.entity.memory.MemoryEvent;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 记忆事件 MyBatis Mapper — MySQL user_memory_event 表。
 */
@Mapper
public interface UserMemoryEventMapper {

    @Insert("INSERT INTO user_memory_event (user_id, event_type, summary, change_detail, " +
            "source_conversation_id, importance, qdrant_memory_id) " +
            "VALUES (#{userId}, #{eventType}, #{summary}, #{changeDetail}, " +
            "#{sourceConversationId}, #{importance}, #{qdrantMemoryId})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(MemoryEvent event);

    @Select("SELECT id, user_id, event_type, summary, change_detail, " +
            "source_conversation_id, importance, qdrant_memory_id, created_at " +
            "FROM user_memory_event WHERE user_id = #{userId} " +
            "ORDER BY created_at DESC LIMIT #{limit}")
    List<MemoryEvent> selectRecentByUserId(@Param("userId") Long userId,
                                           @Param("limit") int limit);

    @Select("SELECT id, user_id, event_type, summary, change_detail, " +
            "source_conversation_id, importance, qdrant_memory_id, created_at " +
            "FROM user_memory_event WHERE user_id = #{userId} AND event_type = #{eventType} " +
            "ORDER BY created_at DESC LIMIT #{limit}")
    List<MemoryEvent> selectByType(@Param("userId") Long userId,
                                   @Param("eventType") String eventType,
                                   @Param("limit") int limit);

    @Delete("DELETE FROM user_memory_event WHERE user_id = #{userId} " +
            "AND created_at < DATE_SUB(NOW(), INTERVAL #{days} DAY)")
    int deleteOlderThan(@Param("userId") Long userId, @Param("days") int days);
}
