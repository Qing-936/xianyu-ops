package io.github.qing936.xianyuops.module.xianyu.controller.agentapi.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "闲鱼 Agent - 心跳注册 Response VO")
@Data
public class HeartbeatRespVO {

    @Schema(description = "本 Agent ID（注册或已有）")
    private Long agentId;

    @Schema(description = "服务端当前时间 ISO-8601（Agent 时钟漂移校正）", example = "2026-09-09T10:30:00")
    private String serverTime;

    @Schema(description = "建议心跳周期（秒）")
    private Integer pollIntervalSec;

    @Schema(description = "当前 pending 回复任务数（管理端提示用）")
    private Long pendingTaskCount;

    @Schema(description = "专属 Token 明文（仅首次注册/补发时返回一次，Agent 须持久化到 .agent_token；其余心跳为 null）")
    private String agentToken;

}
