package io.github.qing936.xianyuops.module.xianyu.controller.admin.agent.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 闲鱼 Agent 注册码 Response VO（不含注册码明文与哈希）")
@Data
public class XianyuAgentEnrollRespVO {

    @Schema(description = "注册码编号")
    private Long id;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "状态：0 待使用 / 1 已使用 / 2 已过期（派生值，见 XianyuAgentEnrollStatusEnum）")
    private Integer status;

    @Schema(description = "过期时间")
    private LocalDateTime expireTime;

    @Schema(description = "被消费时间（未使用为 null）")
    private LocalDateTime usedTime;

    @Schema(description = "消费该码注册的 Agent 编号（未使用为 null）")
    private Long agentId;

    @Schema(description = "消费该码注册的 Agent 名称（未使用为 null）")
    private String agentName;

    @Schema(description = "生成时间")
    private LocalDateTime createTime;

}
