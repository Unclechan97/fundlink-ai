package com.fundlink.ai.constant;

/**
 * AI 任务类型常量。
 * <p>
 * 统一管理各业务场景使用的 taskType 值，替代散落的裸字符串。
 */
public final class TaskType {

    private TaskType() {}

    /** 单接口闭环 (Agent Loop) */
    public static final String LOOP = "LOOP";

    /** 多接口闭环 */
    public static final String MULTI_LOOP = "MULTI_LOOP";

    /** 单接口需求解析（非闭环，/analyze 直接返回） */
    public static final String REQUIREMENT = "REQUIREMENT";

    /** 问题排查/诊断 */
    public static final String DIAGNOSIS = "DIAGNOSIS";
}
