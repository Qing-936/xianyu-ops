package io.github.qing936.xianyuops.module.xianyu.controller.admin.replytask.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Schema(description = "管理后台 - 创建闲鱼回复任务 Request VO")
@Data
public class XianyuReplyTaskCreateReqVO {

    @Schema(description = "目标闲鱼账号 ID", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "目标闲鱼账号不能为空")
    private Long accountId;

    @Schema(description = "目标买家昵称（会话键）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "目标买家昵称不能为空")
    @Size(max = 128, message = "目标买家昵称不能超过 128 个字符")
    private String buyerNick;

    @Schema(description = "回复哪条消息（可空 = 主动发起会话）")
    private Long messageId;

    @Schema(description = "会话绑定闲鱼商品 ID（可空=未绑定商品会话不传；Agent 回复定位弱校验入参，13 号 §4.3）")
    private Long itemId;

    @Schema(description = "会话唯一标识（= 会话 sessionId；out 消息落库 convId 与 in 同源，防同买家多会话分裂）")
    @Size(max = 64, message = "会话唯一标识不能超过 64 个字符")
    private String chatId;

    @Schema(description = "内容类型：text/image/emoji（缺省 text）")
    @Size(max = 16, message = "内容类型不能超过 16 个字符")
    private String contentType;

    @Schema(description = "媒体内容（image 需 url；emoji 需 alt）")
    @Valid
    private MediaVO media;

    @Schema(description = "回复内容（text 必填；媒体可空，服务端补占位）")
    @Size(max = 5000, message = "回复内容不能超过 5000 个字符")
    private String content;

    @Schema(description = "媒体内容")
    @Data
    public static class MediaVO {

        @Schema(description = "媒体类型：image/emoji")
        @Size(max = 16, message = "媒体类型不能超过 16 个字符")
        private String type;

        @Schema(description = "图片 URL（image 必填）")
        @Size(max = 4096, message = "媒体 URL 不能超过 4096 个字符")
        private String url;

        @Schema(description = "表情 token（emoji 必填，如 [微笑]）")
        @Size(max = 500, message = "表情 token 不能超过 500 个字符")
        private String alt;
    }

}
