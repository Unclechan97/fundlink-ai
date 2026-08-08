package com.fundlink.ai.agent.loop;

/**
 * Task phase enum — design §5
 */
public enum TaskPhase {
    ANALYZE,
    VALIDATE,
    DRYRUN,
    DIAGNOSE,
    DECISION_POINT,
    PUBLISH,
    PUBLISHED,
    FAILED
}
