package io.github.qing936.xianyuops.module.xianyu.controller.admin.agent.vo;

import io.github.qing936.xianyuops.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@Schema(description = "管理后台 - 闲鱼机器 Agent 事件分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class XianyuAgentEventPageReqVO extends PageParam {

    @Schema(description = "事件类型")
    private String eventType;

    @Schema(description = "级别：info/warn/error")
    private String level;

}
