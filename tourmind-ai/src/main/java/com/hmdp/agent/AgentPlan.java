package com.hmdp.agent;

import lombok.Builder;
import lombok.Data;
import java.util.Collections;
import java.util.List;

/**
 * Planner 执行计划 — 复杂问题分解为子任务及其依赖关系。
 */
@Data
public class AgentPlan {

    /** false → 简单 query，直接走原 ReACT 循环 */
    private boolean isComplex;

    /** 子任务列表（按依赖分层执行） */
    private List<SubTask> subTasks = Collections.emptyList();

    // ==================== SubTask ====================

    @Data
    @Builder
    public static class SubTask {
        /** 子任务 ID */
        private String id;
        /** 独立可回答的子问题 */
        private String question;
        /** 依赖的子任务 ID 列表（空 = 无依赖，可并行） */
        @Builder.Default
        private List<String> dependsOn = Collections.emptyList();
    }
}
