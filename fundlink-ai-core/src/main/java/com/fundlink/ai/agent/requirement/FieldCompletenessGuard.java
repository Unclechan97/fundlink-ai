package com.fundlink.ai.agent.requirement;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 字段完整性守卫 — 检查 interface_schema 中所有字段是否都在 field_mappings 中有映射。
 * <p>
 * 核心原则: 接口文档列出的每个字段都不能在模板中遗漏。渲染成功 ≠ 字段完整。
 */
public final class FieldCompletenessGuard {

    private FieldCompletenessGuard() {}

    /**
     * 检查 interface_schema.fields 中哪些字段在 field_mappings 中缺失。
     *
     * @param result RequirementAgent 解析结果
     * @return 缺失的字段名列表 (fund_field 大小写不敏感比对)；空列表表示完整
     */
    public static List<String> missingFields(RequirementResult result) {
        List<String> missing = new ArrayList<>();

        InterfaceSchema schema = result.getInterfaceSchema();
        if (schema == null || schema.getFields() == null || schema.getFields().isEmpty()) {
            return missing;
        }

        List<FieldMappingSuggestion> mappings = result.getFieldMappings();

        // 收集已映射的 fundField（小写 trimmed，用于大小写不敏感比对）
        Set<String> mappedFields = Set.of();
        if (mappings != null && !mappings.isEmpty()) {
            mappedFields = mappings.stream()
                    .map(m -> m.getFundField())
                    .filter(f -> f != null && !f.isBlank())
                    .map(f -> f.trim().toLowerCase())
                    .collect(Collectors.toSet());
        }

        for (InterfaceField field : schema.getFields()) {
            String name = field.getName();
            if (name == null || name.isBlank()) {
                continue;
            }
            if (!mappedFields.contains(name.trim().toLowerCase())) {
                missing.add(name.trim());
            }
        }

        return missing;
    }
}
