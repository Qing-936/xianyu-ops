package io.github.qing936.xianyuops.module.xianyu.controller.admin.replytask.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "管理后台 - 闲鱼表情目录 Response VO")
@Data
public class XianyuEmojiCatalogRespVO {

    @Schema(description = "表情名称（如 微笑）")
    private String name;

    @Schema(description = "表情 token（如 [微笑]）")
    private String alt;

    @Schema(description = "表情图片 URL（展示用）")
    private String url;
}
