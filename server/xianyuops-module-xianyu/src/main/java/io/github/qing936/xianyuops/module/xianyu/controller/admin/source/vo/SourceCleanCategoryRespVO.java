package io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "管理后台 - 货源清洗分类选项 VO")
@Data
public class SourceCleanCategoryRespVO {
    private String categoryCode;
    private String categoryName;
    private String groupName;
    private Integer sortOrder;
}
