package io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;

@Schema(description = "管理后台 - 货源图片微调预览 Response VO")
@Data
@AllArgsConstructor
public class SourceImageRefinePreviewRespVO {

    @Schema(description = "微调后图片地址（已转存 OSS，尚未落库）", requiredMode = Schema.RequiredMode.REQUIRED)
    private String imageUrl;
}
