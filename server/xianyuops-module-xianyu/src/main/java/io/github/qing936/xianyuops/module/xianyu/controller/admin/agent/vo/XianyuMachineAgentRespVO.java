package io.github.qing936.xianyuops.module.xianyu.controller.admin.agent.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 闲鱼机器 Agent Response VO")
@Data
public class XianyuMachineAgentRespVO {

    @Schema(description = "Agent 编号")
    private Long id;

    @Schema(description = "Agent 名称")
    private String name;

    @Schema(description = "主机名")
    private String hostName;

    @Schema(description = "状态：online/offline")
    private String status;

    @Schema(description = "最近是否锁屏")
    private Boolean screenLocked;

    @Schema(description = "Agent 程序版本")
    private String agentVersion;

    @Schema(description = "最后心跳时间")
    private LocalDateTime lastHeartbeatAt;

    @Schema(description = "绑定闲鱼号数量（含未对齐行）")
    private Long accountCount;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "是否已激活专属 Token（首次注册领取后为 true；重置后仍为 true）")
    private Boolean tokenActivated;

}
