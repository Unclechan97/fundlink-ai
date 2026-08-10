-- Multi-Loop 重构: ai_task 表新增字段
-- 在 fundlink 数据库执行:
--   mysql -u root -p fundlink < D:\xyFund\fundlink-ai\fundlink-ai-app\src\main\resources\db\migration-multi-loop.sql

ALTER TABLE ai_task
    ADD COLUMN parent_task_id BIGINT       DEFAULT NULL COMMENT '父任务ID，子任务指向主任务';

ALTER TABLE ai_task
    ADD COLUMN interface_id   VARCHAR(100) DEFAULT NULL COMMENT '接口标识，如 LOAN_APPLY';

ALTER TABLE ai_task
    ADD COLUMN interface_name VARCHAR(200) DEFAULT NULL COMMENT '接口名称，如 放款申请';
