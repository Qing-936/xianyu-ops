package io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Schema(description = "管理后台 - 货源库人工入库 Request VO")
@Data
public class SourceInventoryManualImportReqVO {

    @Schema(description = "闲鱼商品详情链接列表，一行一个链接", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty(message = "请至少输入一个闲鱼商品链接")
    @Size(max = 100, message = "单次最多导入 100 个闲鱼商品链接")
    private List<@NotBlank(message = "闲鱼商品链接不能为空")
            @Size(max = 1024, message = "闲鱼商品链接长度不能超过 1024 个字符") String> detailUrls;

}
