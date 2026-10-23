package io.github.qing936.xianyuops.module.xianyu.controller.admin.agent.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 闲鱼机器 Agent 账号绑定 Response VO")
@Data
public class XianyuAgentAccountRespVO {

    @Schema(description = "闲鱼账号 ID（未对齐为 null，前端显示「未绑定」告警）")
    private Long accountId;

    @Schema(description = "授权 ID 快照")
    private String authorizeId;

    @Schema(description = "闲鱼号昵称快照")
    private String userNick;

    @Schema(description = "店铺名称（join app_xianyu_account，未绑定为 null）")
    private String shopName;

    @Schema(description = "登录状态：online/offline/unknown")
    private String loginStatus;

    @Schema(description = "最近一次上报时间")
    private LocalDateTime lastReportAt;

    @Schema(description = "是否已绑定主数据（accountId != null）")
    private Boolean bound;

}
