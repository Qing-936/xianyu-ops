package io.github.qing936.xianyuops.module.xianyu.service.config;

import io.github.qing936.xianyuops.framework.common.util.json.JsonUtils;
import io.github.qing936.xianyuops.framework.mybatis.core.query.LambdaQueryWrapperX;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.config.vo.ConfigFileDetailRespVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.config.vo.ConfigFileRespVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.config.vo.ConfigFileSaveReqVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.config.vo.ConfigServiceRespVO;
import io.github.qing936.xianyuops.module.xianyu.dal.data.config.ConfigFileDO;
import io.github.qing936.xianyuops.module.xianyu.dal.mysql.config.ConfigFileMapper;
import io.github.qing936.xianyuops.module.xianyu.service.source.clean.SourceCleanSkills;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Service
@Validated
@Slf4j
public class ConfigFileServiceImpl implements ConfigFileService {

    private static final String SERVICE_SOURCE_CLEAN = "source_clean";
    private static final String FILE_SKILLS_MD = "skills.md";
    private static final String FILE_CATEGORIES_JSON = "categories.json";

    private record FileMeta(String fileName, String tag, String description, int sortOrder) {
    }

    private record ServiceMeta(String code, String name, String entryDesc, String icon,
                               boolean enabled, List<FileMeta> files) {
    }

    private static final List<ServiceMeta> SERVICES = List.of(
            new ServiceMeta(SERVICE_SOURCE_CLEAN, "货源 AI 清洗", "货源管理 → 货源库 → AI 清洗", "flask", true,
                    List.of(
                            new FileMeta(FILE_SKILLS_MD, "prompt", "技能库：标题 / 文案 / 头图提示词", 10),
                            new FileMeta(FILE_CATEGORIES_JSON, "data", "分类目录与技能路由范围", 20)
                    )),
            new ServiceMeta("ops_assistant", "运营助手", "页面右下角悬浮按钮", "screen", false, List.of())
    );

    @Resource
    private ConfigFileMapper configFileMapper;

    @Resource
    private SkillMarkdownCompiler compiler;

    @Resource
    private SourceCleanSkills sourceCleanSkills;

    @Override
    public List<ConfigServiceRespVO> getServices() {
        ensureSeeded(SERVICE_SOURCE_CLEAN);
        return SERVICES.stream().map(meta -> {
            ConfigServiceRespVO vo = new ConfigServiceRespVO();
            vo.setServiceCode(meta.code());
            vo.setServiceName(meta.name());
            vo.setEntryDesc(meta.entryDesc());
            vo.setIcon(meta.icon());
            vo.setEnabled(meta.enabled());
            vo.setFileCount(meta.enabled() ? meta.files().size() : 0);
            return vo;
        }).toList();
    }

    @Override
    public List<ConfigFileRespVO> getFiles(String serviceCode) {
        requireService(serviceCode);
        ensureSeeded(serviceCode);
        return configFileMapper.selectList(new LambdaQueryWrapperX<ConfigFileDO>()
                        .eq(ConfigFileDO::getServiceCode, serviceCode)
                        .orderByAsc(ConfigFileDO::getSortOrder))
                .stream().map(this::toRespVO).toList();
    }

    @Override
    public ConfigFileDetailRespVO getFile(String serviceCode, String fileName) {
        requireService(serviceCode);
        ensureSeeded(serviceCode);
        ConfigFileDO file = getFileOrThrow(serviceCode, fileName);
        ConfigFileDetailRespVO vo = new ConfigFileDetailRespVO();
        fill(vo, file);
        vo.setContent(file.getContent());
        return vo;
    }

    @Override
    public void saveFile(ConfigFileSaveReqVO reqVO) {
        requireService(reqVO.getServiceCode());
        requireFile(reqVO.getServiceCode(), reqVO.getFileName());
        ensureSeeded(reqVO.getServiceCode());

        validateContent(reqVO.getServiceCode(), reqVO.getFileName(), reqVO.getContent(), true);

        ConfigFileDO file = getFileOrThrow(reqVO.getServiceCode(), reqVO.getFileName());
        file.setContent(reqVO.getContent());
        file.setModified(true);
        configFileMapper.updateById(file);

        reloadIfNeeded(reqVO.getServiceCode());
    }

    @Override
    public void resetFile(String serviceCode, String fileName) {
        requireService(serviceCode);
        requireFile(serviceCode, fileName);
        ensureSeeded(serviceCode);

        String defaultContent = readResource(serviceCode, fileName);
        validateContent(serviceCode, fileName, defaultContent, false);

        ConfigFileDO file = getFileOrThrow(serviceCode, fileName);
        file.setContent(defaultContent);
        file.setModified(false);
        configFileMapper.updateById(file);

        reloadIfNeeded(serviceCode);
    }

    // ==================== 内部 ====================

    private void validateContent(String serviceCode, String fileName, String content, boolean logOnError) {
        if (fileName.endsWith(".json")) {
            try {
                JsonUtils.parseTree(content);
            } catch (Exception e) {
                throw new IllegalArgumentException("保存失败：JSON 格式错误，请检查括号、引号和逗号是否正确");
            }
            return;
        }
        if (fileName.endsWith(".md")) {
            JsonNode categoriesRoot = JsonUtils.parseTree(readFileContent(serviceCode, FILE_CATEGORIES_JSON));
            try {
                compiler.compile(content, categoriesRoot);
            } catch (SkillMarkdownCompiler.CompileException e) {
                if (logOnError) {
                    log.warn("[validateContent][{} 编译失败] {}", fileName, e.getMessage());
                }
                throw new IllegalArgumentException("保存失败：\n" + e.getMessage());
            }
        }
    }

    /** 读取同服务下的文件内容：优先数据库，缺失时回退资源默认 */
    private String readFileContent(String serviceCode, String fileName) {
        ConfigFileDO file = configFileMapper.selectOne(new LambdaQueryWrapperX<ConfigFileDO>()
                .eq(ConfigFileDO::getServiceCode, serviceCode)
                .eq(ConfigFileDO::getFileName, fileName));
        if (file != null && file.getContent() != null) {
            return file.getContent();
        }
        return readResource(serviceCode, fileName);
    }

    private void reloadIfNeeded(String serviceCode) {
        if (SERVICE_SOURCE_CLEAN.equals(serviceCode)) {
            sourceCleanSkills.reload();
        }
    }

    /** 首次访问时按资源默认值幂等种子化（已存在则跳过） */
    private void ensureSeeded(String serviceCode) {
        ServiceMeta meta = requireService(serviceCode);
        if (!meta.enabled()) {
            return;
        }
        Long count = configFileMapper.selectCount(new LambdaQueryWrapperX<ConfigFileDO>()
                .eq(ConfigFileDO::getServiceCode, serviceCode));
        if (count != null && count > 0) {
            return;
        }
        for (FileMeta fileMeta : meta.files()) {
            ConfigFileDO file = new ConfigFileDO();
            file.setServiceCode(serviceCode);
            file.setFileName(fileMeta.fileName());
            file.setTag(fileMeta.tag());
            file.setDescription(fileMeta.description());
            file.setContent(readResource(serviceCode, fileMeta.fileName()));
            file.setModified(false);
            file.setSortOrder(fileMeta.sortOrder());
            try {
                configFileMapper.insert(file);
            } catch (DuplicateKeyException ignored) {
                // 并发首访时另一请求已插入，忽略即可
            }
        }
    }

    private ServiceMeta requireService(String serviceCode) {
        return SERVICES.stream()
                .filter(meta -> meta.code().equals(serviceCode))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知服务：" + serviceCode));
    }

    private void requireFile(String serviceCode, String fileName) {
        ServiceMeta meta = requireService(serviceCode);
        boolean exists = meta.files().stream().anyMatch(file -> file.fileName().equals(fileName));
        if (!exists) {
            throw new IllegalArgumentException("未知配置文件：" + fileName);
        }
    }

    private ConfigFileDO getFileOrThrow(String serviceCode, String fileName) {
        ConfigFileDO file = configFileMapper.selectOne(new LambdaQueryWrapperX<ConfigFileDO>()
                .eq(ConfigFileDO::getServiceCode, serviceCode)
                .eq(ConfigFileDO::getFileName, fileName));
        if (file == null) {
            throw new IllegalArgumentException("配置文件不存在：" + fileName);
        }
        return file;
    }

    private String readResource(String serviceCode, String fileName) {
        ClassPathResource resource = new ClassPathResource("config/" + serviceCode + "/" + fileName);
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalArgumentException("缺少默认配置资源：config/" + serviceCode + "/" + fileName);
        }
    }

    private ConfigFileRespVO toRespVO(ConfigFileDO file) {
        ConfigFileRespVO vo = new ConfigFileRespVO();
        fill(vo, file);
        return vo;
    }

    private void fill(ConfigFileRespVO vo, ConfigFileDO file) {
        vo.setServiceCode(file.getServiceCode());
        vo.setFileName(file.getFileName());
        vo.setTag(file.getTag());
        vo.setDescription(file.getDescription());
        vo.setModified(file.getModified());
        vo.setUpdateTime(file.getUpdateTime());
    }
}
