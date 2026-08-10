package com.fundlink.ai.constant;

/**
 * AI 任务状态常量。
 * <p>
 * 定义闭环和排查共用的任务状态值。
 */
public final class TaskStatus {

    private TaskStatus() {}

    // ── 通用 ──
    public static final String PENDING = "PENDING";
    public static final String FAILED = "FAILED";
    public static final String ABORTED = "ABORTED";

    // ── 闭环专用 (Agent Loop) ──
    public static final String ANALYZE = "ANALYZE";
    public static final String VALIDATE = "VALIDATE";
    public static final String DRYRUN = "DRYRUN";
    public static final String DIAGNOSE = "DIAGNOSE";
    public static final String DECISION_POINT = "DECISION_POINT";
    public static final String PUBLISHED = "PUBLISHED";

    // ── 排查专用 (Troubleshooting) ──
    /** 诊断进行中（RAG 检索 + Tool Calling） */
    public static final String DIAGNOSING = "DIAGNOSING";
    /** 诊断完成 */
    public static final String COMPLETED = "COMPLETED";
}
