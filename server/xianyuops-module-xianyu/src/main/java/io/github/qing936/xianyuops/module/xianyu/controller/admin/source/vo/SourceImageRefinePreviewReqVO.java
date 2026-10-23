package io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Schema(description = "管理后台 - 货源图片微调预览 Request VO")
@Data
public class SourceImageRefinePreviewReqVO {

    @Schema(description = "货源编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1024")
    @NotNull(message = "货源编号不能为空")
    private Long inventoryId;

    @Schema(description = "微调指令", requiredMode = Schema.RequiredMode.REQUIRED, example = "把价格改为 0.01 美刀")
    @NotBlank(message = "微调指令不能为空")
    @Size(max = 500, message = "微调指令长度不能超过 500 个字符")
    private String prompt;
}
