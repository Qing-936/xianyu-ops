package io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Schema(description = "管理后台 - 货源清洗 Skill 选项 VO")
@Data
public class SourceCleanSkillRespVO {

    @Schema(description = "Skill 编码，提交清洗任务时按字段传入 skills", example = "title_standard")
    private String skillCode;

    @Schema(description = "Skill 类型：title/description/images", example = "title")
    private String skillType;

    @Schema(description = "Skill 名称", example = "标准标题")
    private String skillName;

    @Schema(description = "版本", example = "1.0.0")
    private String version;

    private String description;

    private List<String> categoryScopes;

    private Integer sortOrder;

    private Boolean supportsReferenceImage;
}
