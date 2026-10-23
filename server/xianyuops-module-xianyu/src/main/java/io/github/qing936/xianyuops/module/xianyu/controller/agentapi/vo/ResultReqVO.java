package io.github.qing936.xianyuops.module.xianyu.controller.agentapi.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Schema(description = "闲鱼 Agent - 回复任务结果回传 Request VO")
@Data
public class ResultReqVO {

    @Schema(description = "执行结果：success / failed（send_unknown 场景走事件接口，不回传结果）",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "success", allowableValues = {"success", "failed"})
    @NotBlank(message = "执行结果不能为空")
    private String status;

    @Schema(description = "执行结果说明（失败原因）")
    @Size(max = 500, message = "结果说明不能超过 500 个字符")
    private String resultMsg;

    @Schema(description = "发送后验证截图 URL（OSS，由 Agent 经文件上传接口获取）")
    @Size(max = 512, message = "验证截图 URL 不能超过 512 个字符")
    private String verifyImageUrl;

}
