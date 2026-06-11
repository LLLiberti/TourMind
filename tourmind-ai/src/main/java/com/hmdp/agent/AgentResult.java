package com.hmdp.agent;

/**
 * Agent 执行结果 — 包含最终回答和执行追踪。
 *
 * @param answer LLM 最终生成的回答文本
 * @param trace  完整的执行步骤追踪
 */
public record AgentResult(String answer, AgentTrace trace) {
}
