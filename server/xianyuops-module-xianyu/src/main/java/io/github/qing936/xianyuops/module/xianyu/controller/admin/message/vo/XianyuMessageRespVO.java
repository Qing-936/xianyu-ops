package io.github.qing936.xianyuops.module.xianyu.controller.admin.message.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 闲鱼消息 Response VO")
@Data
public class XianyuMessageRespVO {

    @Schema(description = "消息编号")
    private Long id;

    @Schema(description = "闲鱼账号 ID（未对齐为 null）")
    private Long accountId;

    @Schema(description = "账号昵称快照（未绑定账号的唯一标识）")
    private String accountNick;

    @Schema(description = "买家昵称")
    private String buyerNick;

    @Schema(description = "会话绑定闲鱼商品 ID（可空=未关联商品会话）")
    private Long itemId;

    @Schema(description = "方向：in/out")
    private String direction;

    @Schema(description = "消息类型：text/image/emoji/audio/video/order/card/other")
    private String contentType;

    @Schema(description = "消息内容；媒体未提供内容时默认为 [图片]/[表情]/[语音]/[视频]")
    private String content;

    @Schema(description = "结构化快照 JSON：商品卡 ext 或媒体 {type:'media',sourceMessageId?,items:[{type,url?,posterUrl?,alt?,"
            + "duration?,unavailableReason?}],dedupKey}；duration 单位秒，dedupKey 为服务端 SHA256")
    private String extJson;

    @Schema(description = "闲鱼侧消息时间（OCR 估算）")
    private LocalDateTime receiveTime;

    @Schema(description = "消息状态：unread/read/replied")
    private String msgStatus;

    @Schema(description = "关联回复任务 ID（仅 out 消息；前端据此把任务状态回填到气泡）")
    private Long taskId;

    @Schema(description = "发送状态（仅 out）：sending/sent/failed/unknown；NULL=存量已发出消息")
    private String sendStatus;

    @Schema(description = "发送失败/结果未知原因（仅 out，sendStatus=failed/unknown 时有值）")
    private String failReason;

    @Schema(description = "入库时间")
    private LocalDateTime createTime;

}
