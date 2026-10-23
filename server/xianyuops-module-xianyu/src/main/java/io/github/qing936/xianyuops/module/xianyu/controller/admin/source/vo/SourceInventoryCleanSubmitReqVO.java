package io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Schema(description = "管理后台 - 货源库 AI 清洗提交请求 VO")
@Data
public class SourceInventoryCleanSubmitReqVO {

    @NotBlank(message = "请求号不能为空")
    @Pattern(regexp = "^[0-9a-fA-F-]{36}$", message = "请求号格式不正确")
    private String clientRequestId;

    private String recommendationTraceId;

    private String catalogVersion;

    /** 头图补充要求（可选，整批共用） */
    @Size(max = 500, message = "头图补充要求长度不能超过 500")
    private String requirementImages;

    /** 标题补充要求（可选，整批共用） */
    @Size(max = 500, message = "标题补充要求长度不能超过 500")
    private String requirementTitle;

    /** 描述补充要求（可选，整批共用） */
    @Size(max = 500, message = "描述补充要求长度不能超过 500")
    private String requirementDescription;

    @Valid
    @NotEmpty(message = "清洗商品不能为空")
    private List<Item> items;

    @Data
    public static class Item {
        @NotNull(message = "货源ID不能为空")
        private Long inventoryId;

        @NotBlank(message = "商品分类不能为空")
        private String categoryCode;

        @NotEmpty(message = "清洗类型不能为空")
        private List<String> cleanTypes;

        @NotEmpty(message = "清洗 Skill 不能为空")
        private Map<String, String> skills;

        @NotNull(message = "是否参考原图不能为空")
        private Boolean referenceOriginalImage;
    }
}
