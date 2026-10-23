package io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 管理后台 - 货源库批量导入结果 VO
 */
@Schema(description = "管理后台 - 货源库批量导入结果 VO")
@Data
public class SourceInventoryImportResultRespVO {

    @Schema(description = "Excel 行号")
    private Integer rowNo;

    @Schema(description = "标题")
    private String title;

    @Schema(description = "导入成功后的货源库记录 ID")
    private Long inventoryId;

    @Schema(description = "是否成功")
    private Boolean success;

    @Schema(description = "失败原因（成功时为空）")
    private String message;

}
