package io.github.qing936.xianyuops.module.xianyu.controller.admin.agent.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 闲鱼机器 Agent 运行事件 Response VO")
@Data
public class XianyuAgentEventRespVO {

    @Schema(description = "事件编号")
    private Long id;

    @Schema(description = "事件类型")
    private String eventType;

    @Schema(description = "级别：info/warn/error")
    private String level;

    @Schema(description = "事件描述")
    private String content;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

}
