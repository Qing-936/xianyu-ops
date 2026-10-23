package io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo;

import io.github.qing936.xianyuops.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 货源库分页 Request VO")
@Data
public class SourceInventoryPageReqVO extends PageParam {

    @Schema(description = "搜索关键词")
    private String searchKeyword;

    @Schema(description = "商品名称")
    private String title;

    @Schema(description = "当前展示状态（0待审核/1已入库/2已创建/3已上架/4已售出/5已下架/6清洗中/7已清洗/8清洗失败）")
    private Integer displayStatus;

    @Schema(description = "入库状态")
    private Integer inventoryStatus;

    @Schema(description = "入库时间开始")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime inventoryTimeStart;

    @Schema(description = "入库时间结束")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime inventoryTimeEnd;
}
