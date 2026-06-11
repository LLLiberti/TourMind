package com.hmdp.agent;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 执行追踪 — 聚合一次 ReACT 循环的所有步骤。
 */
@Data
public class AgentTrace {

    /** 所有步骤记录 */
    private final List<AgentStep> steps = new ArrayList<>();

    /** 总迭代次数 */
    private int totalIterations;

    /** 是否因达到最大迭代次数而终止 */
    private boolean hitMaxIterations;
}
