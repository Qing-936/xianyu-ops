package io.github.qing936.xianyuops.module.xianyu.controller.admin.agent.vo;

import io.github.qing936.xianyuops.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@Schema(description = "管理后台 - 闲鱼机器 Agent 分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class XianyuMachineAgentPageReqVO extends PageParam {

    @Schema(description = "Agent 名称，模糊匹配")
    private String name;

    @Schema(description = "状态：online/offline")
    private String status;

}
