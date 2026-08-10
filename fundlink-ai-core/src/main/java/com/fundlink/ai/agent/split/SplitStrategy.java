package com.fundlink.ai.agent.split;

import java.util.List;

/**
 * 拆分策略接口。
 * 每个策略实现 trySplit()：成功返回 segments 列表，失败返回空列表。
 * DocumentSplitter 按 priority 排序后依次尝试。
 */
public interface SplitStrategy {

    /** 优先级，数字越小越先尝试 */
    int priority();

    /** 尝试拆分，返回空列表表示本策略无法处理 */
    List<InterfaceSegment> trySplit(String documentText);
}
