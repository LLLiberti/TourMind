package com.hmdp.mapper;

import com.hmdp.entity.memory.SessionSummary;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 会话摘要 MyBatis Mapper — MySQL session_summary 表（会话级 upsert 模型）。
 */
@Mapper
public interface SessionSummaryMapper {

    /**
     * Upsert：session_id 为主键，存在则更新（增量合并），不存在则插入。
     */
    @Insert("INSERT INTO session_summary (session_id, user_id, topic, summary, " +
            "user_goal, discussed_entities, current_focus, unresolved_questions, " +
            "round_count, updated_time) " +
            "VALUES (#{sessionId}, #{userId}, #{topic}, #{summary}, " +
            "#{userGoal}, #{discussedEntities}, #{currentFocus}, #{unresolvedQuestions}, " +
            "#{roundCount}, NOW()) " +
            "ON DUPLICATE KEY UPDATE " +
            "topic = VALUES(topic), " +
            "summary = VALUES(summary), " +
            "user_goal = VALUES(user_goal), " +
            "discussed_entities = VALUES(discussed_entities), " +
            "current_focus = VALUES(current_focus), " +
            "unresolved_questions = VALUES(unresolved_questions), " +
            "round_count = VALUES(round_count), " +
            "updated_time = NOW()")
    int upsert(SessionSummary summary);

    @Select("SELECT session_id, user_id, topic, summary, user_goal, " +
            "discussed_entities, current_focus, unresolved_questions, " +
            "round_count, updated_time " +
            "FROM session_summary WHERE session_id = #{sessionId}")
    SessionSummary selectBySessionId(@Param("sessionId") String sessionId);

    @Select("SELECT session_id, user_id, topic, summary, user_goal, " +
            "discussed_entities, current_focus, unresolved_questions, " +
            "round_count, updated_time " +
            "FROM session_summary WHERE user_id = #{userId} " +
            "ORDER BY updated_time DESC LIMIT #{limit}")
    List<SessionSummary> selectRecentByUserId(@Param("userId") Long userId,
                                               @Param("limit") int limit);

    @Delete("DELETE FROM session_summary WHERE session_id = #{sessionId}")
    int deleteBySessionId(@Param("sessionId") String sessionId);

    @Delete("DELETE FROM session_summary WHERE user_id = #{userId} " +
            "AND updated_time < DATE_SUB(NOW(), INTERVAL #{days} DAY)")
    int deleteOlderThan(@Param("userId") Long userId, @Param("days") int days);
}
