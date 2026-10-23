package io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Schema(description = "管理后台 - 货源 AI 清洗推荐请求 VO")
@Data
public class SourceInventoryCleanRecommendReqVO {

    @NotEmpty(message = "货源ID列表不能为空")
    private List<Long> inventoryIds;
}
