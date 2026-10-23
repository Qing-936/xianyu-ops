package io.github.qing936.xianyuops.module.xianyu.controller.admin.agent.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 闲鱼 Agent 注册码生成 Response VO（code 明文仅本次返回）")
@Data
public class XianyuAgentEnrollCreateRespVO {

    @Schema(description = "注册码编号")
    private Long id;

    @Schema(description = "注册码明文（仅本次返回，库中只存 SHA-256；关闭弹窗后无法再查看）")
    private String code;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "过期时间")
    private LocalDateTime expireTime;

}
