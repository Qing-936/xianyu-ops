package io.github.qing936.xianyuops.module.xianyu.controller.admin.replytask.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 闲鱼回复任务 Response VO")
@Data
public class XianyuReplyTaskRespVO {

    @Schema(description = "任务编号")
    private Long id;

    @Schema(description = "目标 Agent")
    private Long agentId;

    @Schema(description = "目标闲鱼账号 ID")
    private Long accountId;

    @Schema(description = "目标买家昵称")
    private String buyerNick;

    @Schema(description = "会话绑定闲鱼商品 ID（可空=未绑定商品会话）")
    private Long itemId;

    @Schema(description = "会话唯一标识（= 会话 sessionId；可空）")
    private String chatId;

    @Schema(description = "回复内容（媒体任务为占位文本）")
    private String content;

    @Schema(description = "内容类型：text/image/emoji")
    private String contentType;

    @Schema(description = "来源：manual/ai")
    private String source;

    @Schema(description = "状态：pending/dispatched/success/failed/canceled/timeout")
    private String status;

    @Schema(description = "执行结果说明（失败原因）")
    private String resultMsg;

    @Schema(description = "发送后验证截图 URL")
    private String verifyImageUrl;

    @Schema(description = "创建人")
    private Long createdBy;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "下发时间（Agent 拉取）")
    private LocalDateTime dispatchedAt;

    @Schema(description = "完成时间")
    private LocalDateTime finishedAt;

}
