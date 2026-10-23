package io.github.qing936.xianyuops.module.infra.controller.admin.codegen.vo.table;

import io.github.qing936.xianyuops.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

import static io.github.qing936.xianyuops.framework.common.util.date.DateUtils.FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND;

@Schema(description = "管理后台 - 表定义分页 Request VO")
@Data
public class CodegenTablePageReqVO extends PageParam {

    @Schema(description = "表名称，模糊匹配", example = "xianyuops")
    private String tableName;

    @Schema(description = "表描述，模糊匹配", example = "XianyuOps")
    private String tableComment;

    @Schema(description = "实体，模糊匹配", example = "XianyuOps")
    private String className;

    @Schema(description = "创建时间", example = "[2022-07-01 00:00:00,2022-07-01 23:59:59]")
    @DateTimeFormat(pattern = FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND)
    private LocalDateTime[] createTime;

}
