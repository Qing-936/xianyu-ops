package io.github.qing936.xianyuops.module.xianyu.controller.agentapi.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Schema(description = "闲鱼 Agent - 批量上报消息 Request VO")
@Data
public class UploadReqVO {

    @Schema(description = "消息列表，单批 ≤ 200 条", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty(message = "上报消息列表不能为空")
    @Size(max = 200, message = "单批消息不能超过 200 条")
    @Valid
    private List<MessageItem> messages;

    @Schema(description = "消息条目")
    @Data
    public static class MessageItem {

        @Schema(description = "Agent 端消息 hash（审计反查用，非唯一键）", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "clientMsgId 不能为空")
        @Size(max = 64, message = "clientMsgId 不能超过 64 个字符")
        private String clientMsgId;

        @Schema(description = "账号定位键（定位闲鱼号）", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "accountKey 不能为空")
        @Valid
        private AccountKeyItem accountKey;

        @Schema(description = "买家 ID（OCR 不一定拿得到）")
        @Size(max = 64, message = "buyerId 不能超过 64 个字符")
        private String buyerId;

        @Schema(description = "买家昵称（会话键）", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "买家昵称不能为空")
        @Size(max = 128, message = "买家昵称不能超过 128 个字符")
        private String buyerNick;

        @Schema(description = "消息内容：商品卡可空；媒体可空，服务端默认 [图片]/[表情]/[语音]/[视频]；其余类型必填")
        @Size(max = 5000, message = "消息内容不能超过 5000 个字符")
        private String content;

        @Schema(description = "消息类型：text/image/emoji/audio/video/order/card/other，缺省为 text，未知类型计单条 failed", example = "text")
        private String contentType;

        @Schema(description = "方向：MVP 仅接受 in；out 由 Java 补录，传 out 计 failed", example = "in")
        private String direction;

        @Schema(description = "闲鱼侧消息时间 ISO-8601（OCR 估算）", requiredMode = Schema.RequiredMode.REQUIRED, example = "2026-09-09T10:30:00")
        @NotBlank(message = "receiveTime 不能为空")
        private String receiveTime;

        @Schema(description = "WS 会话 id（会话唯一标识；agent 从会话行 sessionInfo.sessionId 直取，为会话聚合主键，可空）")
        @Size(max = 64, message = "chatId 不能超过 64 个字符")
        private String chatId;

        @Schema(description = "会话所属闲鱼商品 ID（协议 string 可空，规避 JS 数值精度风险；优先取会话行 itemInfo.itemId，Java 侧解析 Long 落库）", example = "1075784548658")
        @Size(max = 32, message = "itemId 不能超过 32 个字符")
        private String itemId;

        @Schema(description = "结构化快照：商品卡沿用 type/itemId/title/price/delivery/imageUrl/url；"
                + "媒体必填 {type:'media',sourceMessageId?:string,items:[{type:'image'|'emoji'|'audio'|'video',"
                + "url?:string,posterUrl?:string,alt?:string,duration?:number,unavailableReason?:string}]}，items 为 1~20 项。"
                + "sourceMessageId≤128，url/posterUrl≤4096，alt≤500，unavailableReason≤200 字符；duration 为秒，有限数值 0~86400。"
                + "无 url 须有非空 unavailableReason；emoji 可仅用非空 alt 表达。"
                + "URL 仅允许 HTTP(S) 公有域名 alicdn.com/taobaocdn.com/tbcdn.cn/aliyuncs.com/goofish.com"
                + "/dingtalk.com/dingtalk.cn/aliimg.com 及其子域，"
                + "协议相对地址和 HTTP 统一为 HTTPS；禁止 userinfo、非默认端口、控制符及 IP。"
                + "非法媒体计单条 failed；仅保留白名单字段，dedupKey 由服务端计算，忽略客户端值")
        private Map<String, Object> ext;

        @Schema(description = "会话级商品快照-主图 URL（取会话行右侧商品缩略图；管理端左栏/头部展示，可空）")
        @Size(max = 512, message = "productImageUrl 不能超过 512 个字符")
        private String productImageUrl;

        @Schema(description = "会话级商品快照-展示价文本（如 ¥1.00；取会话内最近一张商品卡价格，头部展示，可空）")
        @Size(max = 64, message = "productPriceText 不能超过 64 个字符")
        private String productPriceText;

        @Schema(description = "会话级商品快照-商品标题（取会话行 itemInfo.title；item_id 为空也能展示，可空）")
        @Size(max = 255, message = "productTitle 不能超过 255 个字符")
        private String productTitle;
    }

    @Schema(description = "账号定位键")
    @Data
    public static class AccountKeyItem {

        @Schema(description = "闲鱼号昵称（定位闲鱼号）", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "闲鱼号昵称不能为空")
        @Size(max = 128, message = "闲鱼号昵称不能超过 128 个字符")
        private String userNick;

        @Schema(description = "授权 ID（可空）")
        @Size(max = 64, message = "授权 ID 不能超过 64 个字符")
        private String authorizeId;
    }

}
