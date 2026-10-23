package io.github.qing936.xianyuops.module.xianyu.service.source.clean;

import io.github.qing936.xianyuops.framework.common.util.json.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class SourceCleanResult {
    private final SourceCleanImageStore imageStore;

    public ObjectNode validateAndStore(ObjectNode result, JsonNode input) {
        String schemaVersion = result.path("schema_version").asText();
        require(Set.of("1.1", "1.2").contains(schemaVersion), "不支持的清洗结果版本");
        boolean schemaV12 = "1.2".equals(schemaVersion);
        if (schemaV12) {
            require(result.path("image_reference_used").isBoolean(), "原图参考状态格式错误");
        }
        require(input.path("inventory_id").asText().equals(result.path("inventory_id").asText()), "清洗结果货源ID不匹配");
        require(Set.of("succeeded", "needs_review").contains(result.path("status").asText()), "清洗业务结果未成功");
        JsonNode fields = JsonUtils.parseTree(input.path("clean_types").asText());
        JsonNode skillSnapshots = input.path("skills");
        if (skillSnapshots.isTextual()) {
            skillSnapshots = JsonUtils.parseTree(skillSnapshots.asText());
        }
        require(skillSnapshots.isObject(), "清洗Skill格式错误");
        Set<String> requested = strings(fields);
        require(requested.equals(strings(result.path("applied_clean_types"))), "清洗字段与请求不匹配");
        require(result.path("warnings").isArray(), "清洗警告格式错误");
        for (JsonNode warning : result.path("warnings")) {
            require(warning.isTextual(), "清洗警告格式错误");
        }
        if (!result.path("warnings").isEmpty()) {
            result.put("status", "needs_review");
        }
        for (String field : Set.of("title", "description", "images")) {
            String state = result.path("field_status").path(field).asText();
            require((requested.contains(field) ? "succeeded" : "skipped").equals(state), "清洗字段状态不匹配");
            if (!requested.contains(field)) {
                continue;
            }
            JsonNode skill = skillSnapshots.path(field);
            JsonNode version = result.path("skill_versions").path(field);
            require(skill.path("skill_code").asText().equals(version.path("skill_code").asText())
                    && skill.path("version").asText().equals(version.path("version").asText()), "清洗Skill版本不匹配");
            if (!field.equals("images")) {
                JsonNode text = result.path("cleaned_" + field);
                int max = skill.path("dify_config").path("maxLength").asInt(field.equals("title") ? 512 : 2000);
                require(text.isTextual() && !text.asText().isBlank()
                        && text.asText().codePointCount(0, text.asText().length()) <= max, "清洗文本为空或超过长度限制");
            }
        }
        // 防止工作流误改未选字段；原文始终从本次输入快照恢复。
        for (String field : Set.of("title", "description")) {
            if (!requested.contains(field)) {
                result.put("cleaned_" + field, input.path(field).asText());
            }
        }
        if (!requested.contains("images")) {
            if (schemaV12) {
                require(!result.path("image_reference_used").asBoolean(), "未清洗头图时不能标记使用原图");
            }
            result.set("cleaned_images", JsonUtils.parseTree("[]"));
            return result;
        }
        // 文生图模式：cleaned_images 为工作流根据标题+描述直接生成的图片地址，无原图映射。
        JsonNode images = result.path("cleaned_images");
        Set<String> generated = new HashSet<>();
        for (JsonNode image : images) {
            require(image.isTextual(), "清洗图片格式错误");
            String url = stripImageUrl(image.asText());
            require(!url.isBlank() && generated.add(url), "清洗图片包含空值或重复项");
        }
        int max = skillSnapshots.path("images").path("dify_config").path("maxImages").asInt(6);
        require(!generated.isEmpty() && generated.size() <= max && generated.size() <= 10, "清洗图片数量不合法");
        if (schemaV12) {
            require(generated.size() == 1, "schema 1.2 头图结果必须且只能有一张");
            boolean referenceRequested = input.path("reference_original_image").asBoolean(false);
            boolean referenceUsed = result.path("image_reference_used").asBoolean();
            require(referenceRequested == referenceUsed,
                    referenceRequested ? "已请求参考原图但工作流未使用原图" : "未请求参考原图却标记为已使用");
        }
        // 全部契约检查通过后才产生文件写入。清洗结果仅使用持久化地址。
        ArrayNode permanentImages = (ArrayNode) JsonUtils.parseTree("[]");
        for (String url : generated) {
            permanentImages.add(imageStore.store(url));
        }
        result.set("cleaned_images", permanentImages);
        return result;
    }

    private Set<String> strings(JsonNode array) {
        require(array.isArray(), "清洗数组格式错误");
        Set<String> values = new HashSet<>();
        for (JsonNode item : array) {
            require(item.isTextual() && !item.asText().isBlank() && values.add(item.asText()), "清洗数组包含空值或重复项");
        }
        return values;
    }

    /** 清理 LLM 可能包裹在图片 URL 上的 Markdown 反引号、引号与首尾空白。 */
    private String stripImageUrl(String value) {
        String url = value.trim();
        while (url.length() >= 2
                && ((url.startsWith("`") && url.endsWith("`"))
                || (url.startsWith("\"") && url.endsWith("\""))
                || (url.startsWith("'") && url.endsWith("'")))) {
            url = url.substring(1, url.length() - 1).trim();
        }
        return url;
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw new SourceCleanFailure(message);
        }
    }
}
