package com.fundlink.ai.tools;

/**
 * Tool Calling 循环监听器 — 用于排查链路写入 ai_agent_trace。
 * <p>
 * 可选注入，不影响 ToolCallingLoop 的独立使用。
 */
public interface ToolLoopListener {

    /**
     * 每轮开始前回调。
     * @param round 当前轮次 (1-based)
     */
    void onRoundStart(int round);

    /**
     * 单个 Tool 调用完成后回调。
     * @param round    当前轮次 (1-based)
     * @param toolName Tool 名称
     * @param args     Tool 参数
     * @param result   Tool 返回内容（截断后）
     */
    void onToolCall(int round, String toolName, String args, String result);

    /**
     * 每轮结束后回调。
     * @param round      当前轮次
     * @param toolCount  本轮 Tool 调用数
     * @param durationMs 本轮耗时 (ms)
     */
    void onRoundEnd(int round, int toolCount, long durationMs);

    /**
     * 最终回答就绪时回调。
     * @param totalRounds  总轮次
     * @param totalTools   总 Tool 调用数
     * @param totalDurationMs 总耗时
     */
    void onComplete(int totalRounds, int totalTools, long totalDurationMs);

    /**
     * 空实现 — 方便调用方只覆写需要的方法。
     */
    class Adapter implements ToolLoopListener {
        @Override public void onRoundStart(int round) {}
        @Override public void onToolCall(int round, String toolName, String args, String result) {}
        @Override public void onRoundEnd(int round, int toolCount, long durationMs) {}
        @Override public void onComplete(int totalRounds, int totalTools, long totalDurationMs) {}
    }
}
