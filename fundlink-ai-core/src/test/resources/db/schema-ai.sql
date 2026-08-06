-- FundLink AI 平台数据库初始化 (5张核心表)
-- 与 fundlink 原有 fl_* 表共存

-- 1. AI 任务记录
CREATE TABLE IF NOT EXISTS ai_task (
    id            BIGINT PRIMARY KEY AUTO_INCREMENT,
    task_no       VARCHAR(64)  NOT NULL UNIQUE COMMENT '任务编号',
    task_type     VARCHAR(32)  NOT NULL COMMENT 'REQUIREMENT/TEST_GEN/DIAGNOSIS',
    status        VARCHAR(16)  DEFAULT 'PENDING' COMMENT 'PENDING/RUNNING/DONE/FAILED',
    input_data    MEDIUMTEXT   COMMENT '输入数据(JSON)',
    output_data   MEDIUMTEXT   COMMENT '输出数据(JSON)',
    trace_id      VARCHAR(64)  COMMENT '关联Trace',
    create_time   DATETIME     DEFAULT CURRENT_TIMESTAMP
);

-- 2. LLM 调用审计日志（金融合规核心）
CREATE TABLE IF NOT EXISTS ai_llm_audit (
    id            BIGINT PRIMARY KEY AUTO_INCREMENT,
    call_id       VARCHAR(64)  NOT NULL UNIQUE COMMENT '调用ID',
    provider      VARCHAR(32)  NOT NULL COMMENT 'OPENAI/ANTHROPIC/QWEN/DEEPSEEK',
    model         VARCHAR(64)  NOT NULL,
    token_input   INT          DEFAULT 0,
    token_output  INT          DEFAULT 0,
    cost_amount   DECIMAL(10,6) COMMENT '费用(USD)',
    latency_ms    INT          DEFAULT 0,
    success       TINYINT      DEFAULT 1,
    error_msg     TEXT,
    trace_id      VARCHAR(64),
    create_time   DATETIME     DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_audit_trace (trace_id),
    INDEX idx_audit_time (create_time)
);

-- 3. Agent 执行 Trace
CREATE TABLE IF NOT EXISTS ai_agent_trace (
    id            BIGINT PRIMARY KEY AUTO_INCREMENT,
    trace_id      VARCHAR(64)  NOT NULL UNIQUE,
    task_id       BIGINT,
    agent_name    VARCHAR(64)  NOT NULL,
    step_name     VARCHAR(128),
    input_text    MEDIUMTEXT,
    output_text   MEDIUMTEXT,
    tool_calls    JSON         COMMENT '工具调用记录',
    token_usage   JSON         COMMENT '{input,output,total}',
    latency_ms    INT          DEFAULT 0,
    status        VARCHAR(16)  DEFAULT 'RUNNING',
    error_msg     TEXT,
    start_time    DATETIME,
    end_time      DATETIME,
    INDEX idx_trace_step (trace_id),
    INDEX idx_trace_task (task_id)
);

-- 4. 反馈记录（数据飞轮载体）
CREATE TABLE IF NOT EXISTS ai_feedback (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    task_id         BIGINT,
    feedback_type   VARCHAR(32)  NOT NULL COMMENT 'FIELD_MAPPING/FLOW_DSL/DIAGNOSIS/GENERAL',
    ai_suggestion   MEDIUMTEXT   NOT NULL COMMENT 'AI原始建议(JSON)',
    human_result    MEDIUMTEXT   NOT NULL COMMENT '人工修正后结果(JSON)',
    diff_summary    TEXT         COMMENT '差异摘要',
    category        VARCHAR(32)  COMMENT '修正根因分类',
    provider_code   VARCHAR(64),
    create_time     DATETIME     DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_fb_task (task_id),
    INDEX idx_fb_provider (provider_code)
);

-- 5. 配置审核记录
CREATE TABLE IF NOT EXISTS ai_config_review (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    config_type     VARCHAR(32)  NOT NULL COMMENT 'FIELD_MAPPING/FLOW_DSL/TEMPLATE',
    config_id       BIGINT       COMMENT '关联配置表ID(fl_*)',
    old_content     MEDIUMTEXT,
    new_content     MEDIUMTEXT   COMMENT 'AI生成的新配置',
    diff_json       JSON,
    status          VARCHAR(16)  DEFAULT 'PENDING' COMMENT 'PENDING/APPROVED/REJECTED',
    reviewer        VARCHAR(64),
    review_comment  TEXT,
    create_time     DATETIME     DEFAULT CURRENT_TIMESTAMP,
    review_time     DATETIME
);
