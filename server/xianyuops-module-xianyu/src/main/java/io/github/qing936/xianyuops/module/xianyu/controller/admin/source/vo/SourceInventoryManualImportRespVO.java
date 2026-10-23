package io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Schema(description = "管理后台 - 货源库批量人工入库响应 VO")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SourceInventoryManualImportRespVO {

    @Schema(description = "总处理条数", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer totalCount;

    @Schema(description = "成功条数", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer successCount;

    @Schema(description = "失败条数", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer failedCount;

    @Schema(description = "逐条处理结果", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<ItemResult> results;

    @Schema(description = "管理后台 - 单条人工入库结果")
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ItemResult {

        @Schema(description = "闲鱼商品链接", requiredMode = Schema.RequiredMode.REQUIRED)
        private String detailUrl;

        @Schema(description = "是否成功", requiredMode = Schema.RequiredMode.REQUIRED)
        private Boolean success;

        @Schema(description = "处理结果说明", requiredMode = Schema.RequiredMode.REQUIRED)
        private String message;
    }
}
