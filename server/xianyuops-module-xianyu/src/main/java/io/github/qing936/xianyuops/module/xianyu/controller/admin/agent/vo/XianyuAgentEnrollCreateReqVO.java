package io.github.qing936.xianyuops.module.xianyu.controller.admin.agent.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Schema(description = "管理后台 - 闲鱼 Agent 注册码生成 Request VO")
@Data
public class XianyuAgentEnrollCreateReqVO {

    @Schema(description = "备注（建议填目标机器名，如 xianguanjia-02）", example = "xianguanjia-02")
    @Size(max = 128, message = "备注长度不能超过 128 个字符")
    private String remark;

}
