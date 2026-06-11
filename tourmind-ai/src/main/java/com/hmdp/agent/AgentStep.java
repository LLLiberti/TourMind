package com.hmdp.agent;

import lombok.Builder;
import lombok.Data;

/**
 * Agent 单步执行记录 — ReACT 循环中每一步的 Trace 信息。
 */
@Data
@Builder
public class AgentStep {

    /** 步骤序号（从 0 开始） */
    private int stepNumber;

    /** 步骤类型：TOOL_CALL / OBSERVATION */
    private String type;

    /** 工具名称（TOOL_CALL 时非 null） */
    private String toolName;

    /** 工具参数或文本内容 */
    private String content;

    /** 耗时（毫秒） */
    private long durationMs;
}
