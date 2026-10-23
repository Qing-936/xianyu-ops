package io.github.qing936.xianyuops.module.xianyu.controller.admin.replytask.vo;

import io.github.qing936.xianyuops.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@Schema(description = "管理后台 - 闲鱼回复任务分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class XianyuReplyTaskPageReqVO extends PageParam {

    @Schema(description = "任务状态：pending/dispatched/success/failed/canceled/timeout")
    private String status;

    @Schema(description = "目标 Agent")
    private Long agentId;

    @Schema(description = "目标闲鱼账号 ID")
    private Long accountId;

}
