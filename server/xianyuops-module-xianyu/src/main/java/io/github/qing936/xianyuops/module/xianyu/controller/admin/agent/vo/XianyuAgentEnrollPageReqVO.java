package io.github.qing936.xianyuops.module.xianyu.controller.admin.agent.vo;

import io.github.qing936.xianyuops.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@Schema(description = "管理后台 - 闲鱼 Agent 注册码分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class XianyuAgentEnrollPageReqVO extends PageParam {

    @Schema(description = "备注，模糊匹配")
    private String remark;

    @Schema(description = "状态：0 待使用 / 1 已使用 / 2 已过期", example = "0")
    private Integer status;

}
