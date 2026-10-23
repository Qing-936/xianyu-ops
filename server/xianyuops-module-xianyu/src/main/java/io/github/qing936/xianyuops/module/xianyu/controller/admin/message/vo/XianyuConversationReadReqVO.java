package io.github.qing936.xianyuops.module.xianyu.controller.admin.message.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

@Schema(description = "管理后台 - 闲鱼会话标记已读 Request VO")
@Data
public class XianyuConversationReadReqVO {

    @Schema(description = "闲鱼账号 ID（已对齐会话传该值，未对齐会话传空）")
    private Long accountId;

    @Schema(description = "账号昵称快照（accountId 为空时的会话定位键；未绑定账号按此兜底）")
    private String accountNick;

    @Schema(description = "会话唯一标识（= 落库 chat_id；传空时按 buyerNick 兜底定位，兼容无会话 id 的存量行）")
    private String chatId;

    @Schema(description = "买家昵称（会话键，必填）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty(message = "买家昵称不能为空")
    private String buyerNick;

    @Schema(description = "会话所属闲鱼商品 ID（可空；NULL 匹配 NULL，打开 A 商品会话不得清掉 B 商品会话红点）")
    private Long itemId;

}
