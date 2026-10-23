package io.github.qing936.xianyuops.module.xianyu.service.source.clean;

import io.github.qing936.xianyuops.framework.common.util.json.JsonUtils;
import io.github.qing936.xianyuops.framework.mybatis.core.query.LambdaQueryWrapperX;
import io.github.qing936.xianyuops.framework.tenant.core.context.TenantContextHolder;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo.SourceCleanCategoryRespVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo.SourceCleanSkillRespVO;
import io.github.qing936.xianyuops.module.xianyu.dal.data.config.ConfigFileDO;
import io.github.qing936.xianyuops.module.xianyu.dal.data.SourceCleanSkillDO;
import io.github.qing936.xianyuops.module.xianyu.dal.mysql.config.ConfigFileMapper;
import io.github.qing936.xianyuops.module.xianyu.dal.mysql.SourceCleanSkillMapper;
import io.github.qing936.xianyuops.module.xianyu.service.config.SkillMarkdownCompiler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 货源清洗技能目录：按租户从「服务配置」读取 skills.md / categories.json 并编译缓存。
 * 保存配置后由 {@link #reload()} 清除缓存，实现保存即生效。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SourceCleanSkills {

    private static final String SERVICE_CODE = "source_clean";
    private static final String FILE_SKILLS_MD = "skills.md";
    private static final String FILE_CATEGORIES_JSON = "categories.json";
    /** 缓存有效期：多实例部署下其他实例最多延迟该时长生效（本实例保存时立即清除） */
    private static final long CACHE_TTL_MILLIS = 60_000L;

    private final SourceCleanSkillMapper mapper;
    private final ConfigFileMapper configFileMapper;
    private final SkillMarkdownCompiler compiler;

    /** 租户级编译结果缓存 */
    private final Map<Long, CachedCatalog> cache = new ConcurrentHashMap<>();

    private record CompiledCatalog(Map<String, JsonNode> presets, JsonNode catalogRoot, String version) {
    }

    private record CachedCatalog(CompiledCatalog catalog, long createdAt) {
    }

    /** 配置变更后清除当前租户缓存，下次访问重新编译。 */
    public void reload() {
        cache.remove(TenantContextHolder.getRequiredTenantId());
    }

    private CompiledCatalog catalog() {
        Long tenantId = TenantContextHolder.getRequiredTenantId();
        CachedCatalog cached = cache.get(tenantId);
        if (cached != null && System.currentTimeMillis() - cached.createdAt() < CACHE_TTL_MILLIS) {
            return cached.catalog();
        }
        CompiledCatalog built = buildCatalog();
        cache.put(tenantId, new CachedCatalog(built, System.currentTimeMillis()));
        return built;
    }

    private CompiledCatalog buildCatalog() {
        String md = readConfig(FILE_SKILLS_MD);
        String categoriesJson = readConfig(FILE_CATEGORIES_JSON);
        try {
            return compileCatalog(md, categoriesJson);
        } catch (RuntimeException e) {
            // 库中配置损坏时回退资源默认，保证清洗功能不中断
            log.error("[sourceCleanSkills][配置编译失败，回退默认配置]", e);
            return compileCatalog(readDefault(FILE_SKILLS_MD), readDefault(FILE_CATEGORIES_JSON));
        }
    }

    private CompiledCatalog compileCatalog(String md, String categoriesJson) {
        JsonNode categoriesRoot = JsonUtils.parseTree(categoriesJson);
        List<SkillMarkdownCompiler.CompiledSkill> skills = compiler.compile(md, categoriesRoot);
        Map<String, JsonNode> presets = new LinkedHashMap<>();
        for (SkillMarkdownCompiler.CompiledSkill skill : skills) {
            presets.put(skill.skillCode(), skill.config());
        }
        String version = SERVICE_CODE + ":" + presets.size() + ":"
                + Integer.toUnsignedString(md.hashCode() ^ categoriesJson.hashCode());
        return new CompiledCatalog(presets, categoriesRoot, version);
    }

    /** 读取配置内容：优先数据库，缺失时回退资源默认（未访问过配置页的新租户）。 */
    private String readConfig(String fileName) {
        ConfigFileDO file = configFileMapper.selectOne(new LambdaQueryWrapperX<ConfigFileDO>()
                .eq(ConfigFileDO::getServiceCode, SERVICE_CODE)
                .eq(ConfigFileDO::getFileName, fileName));
        if (file != null && file.getContent() != null) {
            return file.getContent();
        }
        return readDefault(fileName);
    }

    private String readDefault(String fileName) {
        ClassPathResource resource = new ClassPathResource("config/" + SERVICE_CODE + "/" + fileName);
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("缺少货源清洗默认配置：" + fileName, e);
        }
    }

    /** 推荐工作流使用的安全目录，不包含 prompt_template 与 dify_config。 */
    public ObjectNode safeCatalog() {
        CompiledCatalog catalog = catalog();
        ObjectNode output = (ObjectNode) JsonUtils.parseTree("{}");
        output.put("catalog_version", catalog.version());
        output.put("category_code_rule",
                "虚拟商品直接使用三个一级分类编码；实物类必须从 categories 中选择一个 physical. 开头的具体路由编码，不得返回中文名称、physical 或自造分类");
        ArrayNode primaryCategories = output.putArray("primary_categories");
        for (SourceCleanCategoryRespVO category : primaryCategories()) {
            ObjectNode item = primaryCategories.addObject();
            item.put("category_code", category.getCategoryCode());
            item.put("category_name", category.getCategoryName());
        }
        output.set("categories", catalog.catalogRoot().path("categories").deepCopy());
        ArrayNode safeSkills = output.putArray("skills");
        for (JsonNode preset : catalog.presets().values()) {
            safeSkills.add(safeSkill(preset));
        }
        for (SourceCleanSkillDO skill : enabledStoredSkills()) {
            if (!Boolean.TRUE.equals(skill.getCustomized()) || catalog.presets().containsKey(skill.getSkillCode())) {
                continue;
            }
            JsonNode config = JsonUtils.parseTree(skill.getConfig());
            if (config.path("category_scopes").isArray()) {
                safeSkills.add(safeSkill(config));
            }
        }
        return output;
    }

    public List<SourceCleanCategoryRespVO> categories() {
        List<SourceCleanCategoryRespVO> result = new ArrayList<>();
        for (JsonNode category : catalog().catalogRoot().path("categories")) {
            SourceCleanCategoryRespVO option = new SourceCleanCategoryRespVO();
            option.setCategoryCode(category.path("category_code").asText());
            option.setCategoryName(category.path("category_name").asText());
            option.setGroupName(category.path("group_name").asText());
            option.setSortOrder(category.path("sort_order").asInt());
            result.add(option);
        }
        return result;
    }

    /** 前端只展示四个一级分类；physical.* 仅作为推荐与 Skill 路由使用。 */
    public List<SourceCleanCategoryRespVO> primaryCategories() {
        List<SourceCleanCategoryRespVO> result = categories().stream()
                .filter(item -> !item.getCategoryCode().startsWith("physical."))
                .toList();
        List<SourceCleanCategoryRespVO> output = new ArrayList<>(result);
        SourceCleanCategoryRespVO physical = new SourceCleanCategoryRespVO();
        physical.setCategoryCode("physical");
        physical.setCategoryName("实物类");
        physical.setGroupName("实物商品");
        physical.setSortOrder(40);
        output.add(physical);
        return output;
    }

    public Optional<SourceCleanCategoryRespVO> category(String categoryCode) {
        if ("physical".equals(categoryCode)) {
            return primaryCategories().stream().filter(item -> item.getCategoryCode().equals(categoryCode)).findFirst();
        }
        return categories().stream().filter(item -> item.getCategoryCode().equals(categoryCode)).findFirst();
    }

    public String catalogVersion() {
        return catalog().version();
    }

    public boolean compatible(ObjectNode snapshot, String categoryCode) {
        for (JsonNode skill : snapshot) {
            List<String> scopes = stringList(skill.path("category_scopes"));
            boolean compatible = "physical".equals(categoryCode)
                    ? scopes.stream().anyMatch(scope -> scope.startsWith("physical."))
                    : scopes.contains(categoryCode);
            if (!scopes.isEmpty() && !compatible) {
                return false;
            }
        }
        return true;
    }

    private ObjectNode safeSkill(JsonNode source) {
        ObjectNode safe = (ObjectNode) JsonUtils.parseTree("{}");
        copyText(source, safe, "skill_code");
        copyText(source, safe, "skill_type");
        copyText(source, safe, "skill_name");
        copyText(source, safe, "version");
        copyText(source, safe, "description");
        safe.set("category_scopes", source.path("category_scopes").deepCopy());
        safe.put("sort_order", source.path("sort_order").asInt());
        safe.put("supports_reference_image", source.path("supports_reference_image").asBoolean(
                source.path("dify_config").path("supportsReferenceImage").asBoolean(false)));
        return safe;
    }

    private void copyText(JsonNode source, ObjectNode target, String field) {
        target.put(field, source.path(field).asText());
    }

    /** 可用 Skill 选项：配置编译目录，数据库仅补充已启用的租户自定义 Skill。 */
    public List<SourceCleanSkillRespVO> listOptions() {
        Map<String, SourceCleanSkillRespVO> options = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : catalog().presets().entrySet()) {
            options.put(entry.getKey(), toOption(entry.getValue()));
        }
        List<SourceCleanSkillDO> stored = enabledStoredSkills();
        for (SourceCleanSkillDO skill : stored) {
            if (Boolean.TRUE.equals(skill.getCustomized())) {
                options.put(skill.getSkillCode(), toOption(skill));
            }
        }
        return new ArrayList<>(options.values());
    }

    private List<SourceCleanSkillDO> enabledStoredSkills() {
        return mapper.selectList(new LambdaQueryWrapperX<SourceCleanSkillDO>()
                .eq(SourceCleanSkillDO::getEnabled, true)
                .orderByAsc(SourceCleanSkillDO::getSkillType)
                .orderByAsc(SourceCleanSkillDO::getSkillCode));
    }

    private SourceCleanSkillRespVO toOption(JsonNode skill) {
        SourceCleanSkillRespVO option = new SourceCleanSkillRespVO();
        option.setSkillCode(skill.path("skill_code").asText());
        option.setSkillType(skill.path("skill_type").asText());
        option.setSkillName(skill.path("skill_name").asText());
        option.setVersion(skill.path("version").asText());
        option.setDescription(skill.path("description").asText());
        option.setCategoryScopes(stringList(skill.path("category_scopes")));
        option.setSortOrder(skill.path("sort_order").asInt());
        option.setSupportsReferenceImage(skill.path("supports_reference_image").asBoolean(false));
        return option;
    }

    private SourceCleanSkillRespVO toOption(SourceCleanSkillDO skill) {
        SourceCleanSkillRespVO option = new SourceCleanSkillRespVO();
        option.setSkillCode(skill.getSkillCode());
        option.setSkillType(skill.getSkillType());
        option.setSkillName(skill.getSkillName());
        option.setVersion(skill.getVersion());
        JsonNode config = JsonUtils.parseTree(skill.getConfig());
        option.setDescription(config.path("description").asText());
        List<String> scopes = stringList(config.path("category_scopes"));
        option.setCategoryScopes(scopes.isEmpty()
                ? categories().stream().map(SourceCleanCategoryRespVO::getCategoryCode).toList() : scopes);
        option.setSortOrder(config.path("sort_order").asInt(999));
        option.setSupportsReferenceImage(config.path("supports_reference_image").asBoolean(
                config.path("dify_config").path("supportsReferenceImage").asBoolean(false)));
        return option;
    }

    private List<String> stringList(JsonNode array) {
        List<String> values = new ArrayList<>();
        if (array.isArray()) {
            for (JsonNode value : array) {
                if (value.isTextual() && !value.asText().isBlank()) {
                    values.add(value.asText());
                }
            }
        }
        return values;
    }

    /** 调用方在租户事务内持有货源行锁；按 Skill Code 顺序同步，避免不同批次死锁。 */
    public ObjectNode resolve(List<String> fields, Map<String, String> requested, Map<String, String> legacy) {
        ObjectNode output = (ObjectNode) JsonUtils.parseTree("{}");
        Map<String, JsonNode> presets = catalog().presets();
        for (String field : fields.stream().sorted().toList()) {
            String code = requested == null ? null : requested.get(field);
            if (code == null) {
                String key = switch (field) {
                    case "title" -> "titleTemplate";
                    case "description" -> "descriptionTemplate";
                    default -> "imageStyle";
                };
                String choice = legacy == null ? null : legacy.get(key);
                if (field.equals("images")) {
                    // 文生图模式：旧图生图风格（clean/fashion/business/cozy 等）已移除，统一回退到默认文生图头图。
                    code = "image_handwritten_warm_orange";
                } else {
                    code = (field.equals("title") ? "title_" : "desc_") + (choice == null ? "standard" : choice);
                }
            }
            SourceCleanSkillDO stored = mapper.selectOne(new LambdaQueryWrapperX<SourceCleanSkillDO>()
                    .eq(SourceCleanSkillDO::getSkillCode, code));
            JsonNode preset = presets.get(code);
            if (stored == null && preset == null) {
                throw new IllegalArgumentException("未知清洗 Skill：" + code);
            }
            if (stored != null && !Boolean.TRUE.equals(stored.getCustomized()) && preset == null) {
                throw new IllegalArgumentException("未知清洗 Skill：" + code);
            }
            if (stored == null) {
                stored = new SourceCleanSkillDO();
                stored.setSkillCode(code);
                stored.setSkillType(preset.path("skill_type").asText());
                stored.setSkillName(preset.path("skill_name").asText());
                stored.setVersion(preset.path("version").asText());
                stored.setConfig(preset.toString());
                stored.setCustomized(false);
                stored.setEnabled(true);
                stored.setTenantId(TenantContextHolder.getRequiredTenantId());
                mapper.insertPreset(stored);
                // 当前读：REPEATABLE READ 下并发插入后，普通快照读可能仍看不到记录。
                stored = mapper.selectByCodeForUpdate(code);
                if (stored == null) {
                    throw new IllegalArgumentException("清洗Skill已删除，请使用新编码或恢复记录");
                }
            } else if (!Boolean.TRUE.equals(stored.getCustomized()) && preset != null && (
                    !preset.path("skill_type").asText().equals(stored.getSkillType())
                            || !preset.path("skill_name").asText().equals(stored.getSkillName())
                            || !preset.path("version").asText().equals(stored.getVersion())
                            || !preset.toString().equals(stored.getConfig()))) {
                stored.setSkillType(preset.path("skill_type").asText());
                stored.setSkillName(preset.path("skill_name").asText());
                stored.setVersion(preset.path("version").asText());
                stored.setConfig(preset.toString());
                mapper.updateById(stored);
            }
            if (!Boolean.TRUE.equals(stored.getEnabled()) || !field.equals(stored.getSkillType())) {
                throw new IllegalArgumentException("清洗 Skill 已禁用或类型不匹配");
            }
            JsonNode config = JsonUtils.parseTree(stored.getConfig());
            if (!code.equals(config.path("skill_code").asText())
                    || !stored.getVersion().equals(config.path("version").asText())
                    || config.path("prompt_template").asText().isBlank() || !config.path("dify_config").isObject()) {
                throw new IllegalArgumentException("清洗 Skill 配置不完整");
            }
            output.set(field, config);
        }
        return output;
    }
}
