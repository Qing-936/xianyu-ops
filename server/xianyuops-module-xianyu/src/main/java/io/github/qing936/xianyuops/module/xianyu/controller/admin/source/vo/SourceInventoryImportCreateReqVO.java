package io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 管理后台 - 货源库批量导入创建 Request VO
 *
 * <p>items 为解析预览后确认导入的商品列表。</p>
 */
@Schema(description = "管理后台 - 货源库批量导入创建 Request VO")
@Data
public class SourceInventoryImportCreateReqVO {

    @Schema(description = "待导入商品列表", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty(message = "待导入商品列表不能为空")
    @Size(max = 200, message = "单次最多导入 200 条商品")
    @Valid
    private List<SourceInventoryImportItemReqVO> items;

}
