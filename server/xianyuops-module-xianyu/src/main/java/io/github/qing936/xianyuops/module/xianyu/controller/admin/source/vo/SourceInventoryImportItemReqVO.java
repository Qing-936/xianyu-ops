package io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 管理后台 - 货源库批量导入单条商品 Request VO
 */
@Schema(description = "管理后台 - 货源库批量导入单条商品 Request VO")
@Data
public class SourceInventoryImportItemReqVO {

    @Schema(description = "Excel 行号（结果回显用）")
    private Integer rowNo;

    @Schema(description = "标题", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "标题不能为空")
    @Size(max = 512, message = "标题长度不能超过 512 个字符")
    private String title;

    @Schema(description = "商品链接", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "商品链接不能为空")
    @Size(max = 1024, message = "商品链接长度不能超过 1024 个字符")
    private String link;

    @Schema(description = "外部平台商品 ID（从链接提取，用于去重）")
    @Size(max = 64, message = "外部平台商品 ID 长度不能超过 64 个字符")
    private String externalItemId;

    @Schema(description = "价格（分）")
    private Long price;

    @Schema(description = "图片 URL 列表（原始外链）")
    private List<String> images;

    @Schema(description = "卖家昵称")
    @Size(max = 128, message = "卖家昵称长度不能超过 128 个字符")
    private String seller;

    @Schema(description = "成色")
    @Size(max = 32, message = "成色长度不能超过 32 个字符")
    private String itemCondition;

    @Schema(description = "功能状态")
    @Size(max = 64, message = "功能状态长度不能超过 64 个字符")
    private String functionStatus;

}
