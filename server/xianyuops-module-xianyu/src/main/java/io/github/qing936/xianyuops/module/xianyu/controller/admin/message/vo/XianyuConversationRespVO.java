package io.github.qing936.xianyuops.module.xianyu.controller.admin.message.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 闲鱼会话聚合 Response VO")
@Data
public class XianyuConversationRespVO {

    @Schema(description = "闲鱼账号 ID（未绑定会话为 null，前端显示「未绑定账号 + 快照昵称」告警）")
    private Long accountId;

    @Schema(description = "账号昵称快照（未绑定会话的唯一标识）")
    private String accountNick;

    @Schema(description = "买家昵称（会话键）")
    private String buyerNick;

    @Schema(description = "会话唯一标识（= 闲鱼会话 sessionId，即落库 chat_id；空=该会话无会话 id，前端按 buyerNick 兜底）")
    private String chatId;

    @Schema(description = "会话绑定闲鱼商品 ID（可空=未关联商品会话；聚合键维度）")
    private Long itemId;

    @Schema(description = "绑定商品标题（优先取目录标题；未命中时取会话行快照标题，仍无则前端兜底「商品#itemId」）")
    private String productTitle;

    @Schema(description = "绑定商品价格（分；item_id 命中商品表回填）")
    private Long productPrice;

    @Schema(description = "绑定商品主图 URL（item_id 命中商品表回填；未命中时兜底取最近商品卡 ext 的 imageUrl）")
    private String productImageUrl;

    @Schema(description = "绑定商品展示价（商品表未命中时取最近商品卡 ext 的展示价串，如 ¥1.00；命中商品表为 null，前端用 productPrice 分转元）")
    private String productPriceText;

    @Schema(description = "绑定商品详情页链接（商品表未命中时取最近商品卡 ext 的 url，供头部/列表「查看商品」）")
    private String productUrl;

    @Schema(description = "最后一条消息内容")
    private String lastMessageContent;

    @Schema(description = "最后一条消息类型：text/image/order/card/other（card 内容常空，前端据此显示占位，评审 P3-1）")
    private String lastMessageContentType;

    @Schema(description = "最后一条消息方向：in/out")
    private String lastMessageDirection;

    @Schema(description = "最后一条消息时间")
    private LocalDateTime lastMessageTime;

    @Schema(description = "未读数")
    private Long unreadCount;

}
