package io.github.qing936.xianyuops.module.xianyu.service.config;

import io.github.qing936.xianyuops.framework.common.util.json.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 货源 AI 清洗技能库编译器：把运营可读的中文 skills.md 编译为结构化技能 JSON。
 * <p>
 * 语法：每条技能以 {@code ## 名称} 开头，属性行以 {@code @} 开头，提示词写在 ```prompt 围栏内。
 * 编译失败抛出 {@link CompileException}，携带带行号的中文错误信息。
 */
@Component
public class SkillMarkdownCompiler {

    private static final Map<String, String> TYPE_CN_TO_CODE = Map.of(
            "标题", "title",
            "文案", "description",
            "头图", "images"
    );

    private static final Pattern CODE_PATTERN = Pattern.compile("^[a-z0-9_]+$");

    /** 编译后的单条技能 */
    public record CompiledSkill(String skillCode, String skillType, String skillName,
                                String version, ObjectNode config) {
    }

    /** 编译失败，携带全部错误（含行号） */
    public static class CompileException extends RuntimeException {
        private final List<String> errors;

        public CompileException(List<String> errors) {
            super(String.join("\n", errors));
            this.errors = errors;
        }

        public List<String> getErrors() {
            return errors;
        }
    }

    public List<CompiledSkill> compile(String md, JsonNode categoriesRoot) {
        Map<String, String> nameToCode = buildCategoryMap(categoriesRoot);
        String[] lines = md.split("\n", -1);

        List<int[]> blocks = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].startsWith("## ")) {
                blocks.add(new int[]{i, lines.length});
            }
        }
        for (int i = 0; i < blocks.size() - 1; i++) {
            blocks.get(i)[1] = blocks.get(i + 1)[0];
        }

        List<CompiledSkill> result = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        Set<String> codes = new HashSet<>();

        for (int[] block : blocks) {
            int start = block[0];
            int end = block[1];
            int nameLine = start + 1;
            String skillName = lines[start].substring(3).trim();
            if (skillName.isEmpty()) {
                errors.add("第 " + nameLine + " 行：技能名称不能为空");
                continue;
            }

            Map<String, String> attrs = new LinkedHashMap<>();
            Map<String, Integer> attrLines = new HashMap<>();
            String prompt = null;
            int promptLine = 0;
            boolean closed = true;

            int i = start + 1;
            while (i < end) {
                String trimmed = lines[i].trim();
                if (trimmed.startsWith("```prompt")) {
                    promptLine = i + 1;
                    StringBuilder sb = new StringBuilder();
                    int j = i + 1;
                    closed = false;
                    while (j < end) {
                        if (lines[j].trim().equals("```")) {
                            closed = true;
                            break;
                        }
                        if (sb.length() > 0) {
                            sb.append('\n');
                        }
                        sb.append(lines[j]);
                        j++;
                    }
                    prompt = sb.toString();
                    break;
                }
                if (trimmed.startsWith("@")) {
                    int sp = trimmed.indexOf(' ');
                    if (sp <= 1) {
                        errors.add("第 " + (i + 1) + " 行：属性行格式应为「@属性名 值」");
                    } else {
                        String key = trimmed.substring(1, sp);
                        attrs.put(key, trimmed.substring(sp + 1).trim());
                        attrLines.put(key, i + 1);
                    }
                }
                i++;
            }

            if (!closed) {
                errors.add("第 " + promptLine + " 行：prompt 代码块未闭合（缺少结尾 ```）");
                continue;
            }
            if (prompt == null) {
                errors.add("第 " + nameLine + " 行：技能「" + skillName + "」缺少 ```prompt 代码块");
                continue;
            }
            if (prompt.isBlank()) {
                errors.add("第 " + promptLine + " 行：提示词内容不能为空");
                continue;
            }

            // ---- 编码 ----
            String code = attrs.get("编码");
            if (code == null || code.isBlank()) {
                errors.add("第 " + nameLine + " 行：技能「" + skillName + "」缺少 @编码");
                continue;
            }
            if (!CODE_PATTERN.matcher(code).matches()) {
                errors.add("第 " + attrLines.get("编码") + " 行：@编码 只能使用英文小写字母、数字和下划线");
                continue;
            }
            if (!codes.add(code)) {
                errors.add("第 " + attrLines.get("编码") + " 行：@编码「" + code + "」重复");
                continue;
            }

            // ---- 类型 ----
            String typeCn = attrs.get("类型");
            String typeCode = typeCn == null ? null : TYPE_CN_TO_CODE.get(typeCn);
            if (typeCode == null) {
                errors.add("第 " + lineOf(attrLines, "类型", nameLine) + " 行：@类型 只能填「标题」「文案」「头图」");
                continue;
            }

            // ---- 分类 ----
            String scopesRaw = attrs.get("适用分类");
            List<String> scopeCodes = new ArrayList<>();
            if (scopesRaw == null || scopesRaw.isBlank()) {
                errors.add("第 " + nameLine + " 行：技能「" + skillName + "」缺少 @适用分类");
                continue;
            }
            boolean scopeError = false;
            for (String name : scopesRaw.split(",")) {
                String cn = name.trim();
                if (cn.isEmpty()) {
                    continue;
                }
                String mapped = nameToCode.get(cn);
                if (mapped == null) {
                    errors.add("第 " + attrLines.get("适用分类") + " 行：未知分类「" + cn + "」，请先在 categories.json 中定义");
                    scopeError = true;
                } else {
                    scopeCodes.add(mapped);
                }
            }
            if (scopeError) {
                continue;
            }
            if (scopeCodes.isEmpty()) {
                errors.add("第 " + attrLines.get("适用分类") + " 行：@适用分类 至少填一个分类");
                continue;
            }

            // ---- 版本 / 排序 ----
            String version = attrs.get("版本");
            if (version == null || version.isBlank()) {
                errors.add("第 " + nameLine + " 行：技能「" + skillName + "」缺少 @版本");
                continue;
            }
            Integer sortOrder = parseInt(attrs.get("排序"), "排序", attrLines, errors, nameLine);
            if (sortOrder == null) {
                continue;
            }

            ObjectNode config = (ObjectNode) JsonUtils.parseTree("{}");
            config.put("skill_code", code);
            config.put("skill_type", typeCode);
            config.put("skill_name", skillName);
            config.put("version", version);
            config.put("description", attrs.getOrDefault("说明", ""));

            String targets = attrs.get("目标商品");
            if (targets != null && !targets.isBlank()) {
                ArrayNode arr = config.putArray("适用商品");
                for (String t : targets.split(",")) {
                    if (!t.trim().isEmpty()) {
                        arr.add(t.trim());
                    }
                }
            }

            ArrayNode scopeArr = config.putArray("category_scopes");
            scopeCodes.forEach(scopeArr::add);
            config.put("sort_order", sortOrder);

            // ---- 类型专属技术字段 ----
            if ("images".equals(typeCode)) {
                boolean ref = !"否".equals(attrs.getOrDefault("参考图", "是"));
                Integer maxImages = attrs.containsKey("图片数量")
                        ? parseInt(attrs.get("图片数量"), "图片数量", attrLines, errors, nameLine) : 1;
                if (maxImages == null) {
                    continue;
                }
                if (maxImages <= 0) {
                    errors.add("第 " + attrLines.get("图片数量") + " 行：@图片数量 必须是正整数");
                    continue;
                }
                config.put("supports_reference_image", ref);
                config.put("prompt_template", prompt);
                ObjectNode dify = config.putObject("dify_config");
                dify.put("maxImages", maxImages);
                dify.put("supportsReferenceImage", ref);
            } else {
                Integer maxLength = parseInt(attrs.get("最大长度"), "最大长度", attrLines, errors, nameLine);
                if (maxLength == null) {
                    continue;
                }
                if (maxLength <= 0) {
                    errors.add("第 " + attrLines.get("最大长度") + " 行：@最大长度 必须是正整数");
                    continue;
                }
                config.put("prompt_template", prompt);
                ObjectNode dify = config.putObject("dify_config");
                dify.put("maxLength", maxLength);
            }

            result.add(new CompiledSkill(code, typeCode, skillName, version, config));
        }

        if (!errors.isEmpty()) {
            throw new CompileException(errors);
        }
        if (result.isEmpty()) {
            throw new CompileException(List.of("未解析到任何技能，请检查 skills.md 是否以「## 技能名称」开头"));
        }
        return result;
    }

    /** 从 categories.json 构建「中文分类名 -> 分类编码」映射 */
    public static Map<String, String> buildCategoryMap(JsonNode categoriesRoot) {
        Map<String, String> map = new HashMap<>();
        for (JsonNode category : categoriesRoot.path("categories")) {
            String code = category.path("category_code").asText();
            String name = category.path("category_name").asText();
            if (!code.isBlank() && !name.isBlank()) {
                map.put(name.trim(), code);
            }
        }
        return map;
    }

    private static int lineOf(Map<String, Integer> attrLines, String key, int fallback) {
        Integer line = attrLines.get(key);
        return line == null ? fallback : line;
    }

    private static Integer parseInt(String value, String key, Map<String, Integer> attrLines,
                                    List<String> errors, int fallbackLine) {
        if (value == null || value.isBlank()) {
            errors.add("第 " + fallbackLine + " 行：缺少 @" + key);
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            errors.add("第 " + lineOf(attrLines, key, fallbackLine) + " 行：@" + key + " 必须是整数");
            return null;
        }
    }
}
