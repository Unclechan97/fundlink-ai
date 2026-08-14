package com.fundlink.ai.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 增量 schema 保障（B5.2/B6.1 决策字段 + B7 索引）。
 * <p>
 * schema-ai.sql 使用 CREATE TABLE IF NOT EXISTS，对已存在的库不生效 —
 * 这里按 information_schema 逐个补列/补索引，幂等可重入，新旧库通吃。
 * <p>
 * 实现为 {@link ApplicationRunner} 而非 ApplicationReadyEvent 监听：
 * Runner 保证在 ApplicationReadyEvent 发布前执行完毕 —
 * 否则 LoopStartupRecovery 等 ready 监听器可能先于迁移访问 ai_task，
 * 在旧库上直接报 Unknown column（两个监听器的执行顺序不保证）。
 */
@Slf4j
@Component
public class SchemaMigration implements ApplicationRunner {

    private static final Map<String, String> AI_TASK_COLUMNS = new LinkedHashMap<>();

    static {
        AI_TASK_COLUMNS.put("parent_task_id", "BIGINT DEFAULT NULL COMMENT '父任务ID，子任务指向主任务'");
        AI_TASK_COLUMNS.put("interface_id", "VARCHAR(100) DEFAULT NULL COMMENT '接口标识，如 LOAN_APPLY'");
        AI_TASK_COLUMNS.put("interface_name", "VARCHAR(200) DEFAULT NULL COMMENT '接口名称，如 放款申请'");
        AI_TASK_COLUMNS.put("decision_type", "VARCHAR(32) DEFAULT NULL COMMENT '决策类型: PUBLISH_CONFIRM/RECOVERY_EXHAUSTED'");
        AI_TASK_COLUMNS.put("decision_summary", "TEXT COMMENT '决策摘要（展示给用户）'");
        AI_TASK_COLUMNS.put("decision_options", "JSON DEFAULT NULL COMMENT '决策选项列表(JSON数组)'");
        AI_TASK_COLUMNS.put("decision_result", "VARCHAR(32) DEFAULT NULL COMMENT '用户已提交的决策'");
        AI_TASK_COLUMNS.put("decision_time", "DATETIME DEFAULT NULL COMMENT '决策提交时间'");
    }

    private final JdbcTemplate jdbc;

    public SchemaMigration(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        migrate();
    }

    public void migrate() {
        try {
            for (Map.Entry<String, String> col : AI_TASK_COLUMNS.entrySet()) {
                ensureColumn(col.getKey(), col.getValue());
            }
            ensureIndex("idx_ai_task_parent", "ai_task", "parent_task_id");
        } catch (Exception e) {
            log.error("[MIGRATION] Schema migration failed: {}", e.getMessage(), e);
        }
    }

    private void ensureColumn(String name, String ddl) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_task' AND COLUMN_NAME = ?",
                Integer.class, name);
        if (count != null && count == 0) {
            jdbc.execute("ALTER TABLE ai_task ADD COLUMN " + name + " " + ddl);
            log.info("[MIGRATION] ai_task.{} added", name);
        }
    }

    private void ensureIndex(String indexName, String table, String column) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND INDEX_NAME = ?",
                Integer.class, table, indexName);
        if (count != null && count == 0) {
            jdbc.execute("CREATE INDEX " + indexName + " ON " + table + " (" + column + ")");
            log.info("[MIGRATION] index {}.{} created", table, indexName);
        }
    }
}
