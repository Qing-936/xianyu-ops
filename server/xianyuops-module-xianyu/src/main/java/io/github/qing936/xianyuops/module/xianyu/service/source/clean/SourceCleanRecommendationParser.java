package io.github.qing936.xianyuops.module.xianyu.service.source.clean;

import io.github.qing936.xianyuops.framework.common.util.json.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 对推荐模型结果做目录约束校验，失败字段留给前端人工选择。 */
@Component
public class SourceCleanRecommendationParser {

    private static final Map<String, String> FIELD_TYPES = Map.of(
            "title", "title",
            "description", "description",
            "images", "images"
    );

    public ObjectNode parse(ObjectNode modelResult, List<Long> inventoryIds, ObjectNode catalog) {
        Map<String, JsonNode> categories = index(catalog.path("categories"), "category_code");
        Map<String, String> categoryCodesByName = categoryCodesByName(catalog.path("categories"));
        Map<String, JsonNode> skills = index(catalog.path("skills"), "skill_code");
        Map<String, JsonNode> rawByInventory = new HashMap<>();
        JsonNode rawItems = modelResult.path("recommendations");
        if (rawItems.isArray()) {
            for (JsonNode raw : rawItems) {
                String id = raw.path("inventory_id").asText();
                if (!id.isBlank()) {
                    rawByInventory.putIfAbsent(id, raw);
                }
            }
        }

        ObjectNode output = (ObjectNode) JsonUtils.parseTree("{}");
        output.put("schema_version", "1.0");
        output.put("catalog_version", catalog.path("catalog_version").asText());
        ArrayNode normalizedItems = output.putArray("recommendations");
        output.putArray("warnings");
        for (Long inventoryId : inventoryIds) {
            String id = String.valueOf(inventoryId);
            JsonNode raw = rawByInventory.get(id);
            ObjectNode item = normalizedItems.addObject();
            item.put("inventory_id", id);
            ObjectNode choices = item.putObject("recommendations");
            ArrayNode warnings = item.putArray("warnings");
            if (raw == null) {
                item.putNull("routing_category");
                item.put("reference_original_image_recommended", false);
                warnings.add("AI 未返回该商品的推荐，请手动选择");
                continue;
            }

            JsonNode rawCategory = raw.path("category");
            String categoryCode = resolveCategoryCode(rawCategory, categories, categoryCodesByName);
            JsonNode category = categories.get(categoryCode);
            if (category == null) {
                item.putNull("routing_category");
                warnings.add("AI 推荐了不存在的分类，请手动选择");
                categoryCode = "";
            } else {
                ObjectNode normalizedCategory = item.putObject("routing_category");
                normalizedCategory.put("category_code", categoryCode);
                normalizedCategory.put("category_name", category.path("category_name").asText());
                normalizedCategory.put("confidence", confidence(rawCategory));
                normalizedCategory.put("reason", reason(rawCategory));
            }

            for (Map.Entry<String, String> field : FIELD_TYPES.entrySet()) {
                JsonNode rawChoice = raw.path(field.getKey());
                String skillCode = rawChoice.path("skill_code").asText();
                JsonNode skill = skills.get(skillCode);
                if (skill == null || !field.getValue().equals(skill.path("skill_type").asText())) {
                    warnings.add(field.getKey() + " 推荐缺失或类型不匹配，请手动选择");
                    continue;
                }
                Set<String> scopes = strings(skill.path("category_scopes"));
                if (categoryCode.isBlank() || !scopes.contains(categoryCode)) {
                    warnings.add(field.getKey() + " 推荐与分类不兼容，请手动选择");
                    continue;
                }
                ObjectNode choice = choices.putObject(field.getKey());
                choice.put("skill_code", skillCode);
                choice.put("confidence", confidence(rawChoice));
                choice.put("reason", reason(rawChoice));
            }
            item.put("reference_original_image_recommended",
                    raw.path("reference_original_image_recommended").asBoolean(false));
        }
        return output;
    }

    private String resolveCategoryCode(JsonNode rawCategory, Map<String, JsonNode> categories,
                                       Map<String, String> categoryCodesByName) {
        String rawCode = rawCategory.path("category_code").asText().trim();
        if (categories.containsKey(rawCode)) {
            return rawCode;
        }
        String codeByCodeFieldName = categoryCodesByName.get(rawCode);
        if (codeByCodeFieldName != null) {
            return codeByCodeFieldName;
        }
        return categoryCodesByName.getOrDefault(rawCategory.path("category_name").asText().trim(), rawCode);
    }

    private Map<String, String> categoryCodesByName(JsonNode array) {
        Map<String, String> result = new HashMap<>();
        Set<String> duplicates = new HashSet<>();
        if (array.isArray()) {
            for (JsonNode item : array) {
                String name = item.path("category_name").asText().trim();
                String code = item.path("category_code").asText().trim();
                if (!name.isBlank() && !code.isBlank() && result.putIfAbsent(name, code) != null) {
                    duplicates.add(name);
                }
            }
        }
        duplicates.forEach(result::remove);
        return result;
    }

    private Map<String, JsonNode> index(JsonNode array, String key) {
        Map<String, JsonNode> result = new HashMap<>();
        if (array.isArray()) {
            for (JsonNode item : array) {
                String value = item.path(key).asText();
                if (!value.isBlank()) {
                    result.put(value, item);
                }
            }
        }
        return result;
    }

    private Set<String> strings(JsonNode array) {
        Set<String> result = new HashSet<>();
        if (array.isArray()) {
            for (JsonNode item : array) {
                if (item.isTextual()) {
                    result.add(item.asText());
                }
            }
        }
        return result;
    }

    private double confidence(JsonNode value) {
        return Math.max(0D, Math.min(1D, value.path("confidence").asDouble(0D)));
    }

    private String reason(JsonNode value) {
        String reason = value.path("reason").asText().trim();
        if (reason.isBlank()) {
            return "AI 未提供推荐理由";
        }
        return reason.codePointCount(0, reason.length()) <= 80
                ? reason : reason.substring(0, reason.offsetByCodePoints(0, 80));
    }
}
