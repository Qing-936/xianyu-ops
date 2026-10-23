package io.github.qing936.xianyuops.module.xianyu.controller.agentapi.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Schema(description = "闲鱼 Agent - 批量上报消息 Response VO")
@Data
public class UploadRespVO {

    @Schema(description = "新入库条数")
    private Integer acceptedCount;

    @Schema(description = "窗口判重命中的条数")
    private Integer duplicateCount;

    @Schema(description = "校验失败/解析失败的条数")
    private Integer failedCount;

    @Schema(description = "失败明细（仅 failedCount > 0 时返回）")
    private List<FailureItem> failures;

    @Schema(description = "失败条目")
    @Data
    public static class FailureItem {

        @Schema(description = "Agent 端消息 hash")
        private String clientMsgId;

        @Schema(description = "失败原因")
        private String reason;
    }

}
