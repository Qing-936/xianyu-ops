package io.github.qing936.xianyuops.module.xianyu.controller.agentapi.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Schema(description = "闲鱼 Agent - 运行事件上报 Request VO")
@Data
public class EventReqVO {

    @Schema(description = "相关账号（有则尝试对齐出 account_id）")
    @Valid
    private UploadReqVO.AccountKeyItem accountKey;

    @Schema(description = "事件类型", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "login_expired", allowableValues = {
            "login_expired", "screen_locked", "ocr_failed", "send_success",
            "send_failed", "send_unknown", "client_error", "agent_start", "agent_stop"})
    @NotBlank(message = "事件类型不能为空")
    private String eventType;

    @Schema(description = "级别：info/warn/error，默认 info", example = "info")
    private String level;

    @Schema(description = "事件描述")
    @Size(max = 1000, message = "事件描述不能超过 1000 个字符")
    private String content;

    @Schema(description = "明细 JSON")
    private Object rawJson;

}
