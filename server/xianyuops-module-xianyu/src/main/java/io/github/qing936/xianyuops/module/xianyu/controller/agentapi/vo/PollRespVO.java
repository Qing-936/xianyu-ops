package io.github.qing936.xianyuops.module.xianyu.controller.agentapi.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Schema(description = "闲鱼 Agent - 长轮询拉取任务 Response VO")
@Data
public class PollRespVO {

    @Schema(description = "领取到的回复任务（可能为空数组 = 无任务）")
    private List<TaskItem> tasks;

    @Schema(description = "回复任务条目")
    @Data
    public static class TaskItem {

        @Schema(description = "任务 ID")
        private Long taskId;

        @Schema(description = "目标闲鱼号")
        private UploadReqVO.AccountKeyItem accountKey;

        @Schema(description = "目标买家昵称")
        private String buyerNick;

        @Schema(description = "回复内容（text 为正文；媒体任务为占位文本，Agent 仅用于日志）")
        private String content;

        @Schema(description = "内容类型：text/image/emoji（缺省 text）")
        private String contentType;

        @Schema(description = "媒体内容（image 需 url；emoji 需 alt）")
        private MediaItem media;

        @Schema(description = "会话绑定闲鱼商品 ID（string 可空；Agent 打开会话后比对可见卡片集合弱校验，13 号 §4.3/§五）")
        private String itemId;

        @Schema(description = "会话唯一标识（= 会话 sessionId，可空；Agent 可用于精确定位会话，优于昵称+商品弱校验）")
        private String chatId;

        @Schema(description = "该账号×买家会话在库中出现过的商品 ID 集合（string；13 号 §五 增强 B：目标商品卡不在可视窗口时用于兜底放行，避免多商品会话对旧商品的回复被误拒；无 itemId 任务不传）")
        private List<String> knownItems;

        @Schema(description = "任务来源：manual/ai")
        private String source;

        @Schema(description = "媒体内容（type=image/emoji）")
        @Data
        public static class MediaItem {

            @Schema(description = "媒体类型：image/emoji")
            private String type;

            @Schema(description = "图片 URL（image 必填）")
            private String url;

            @Schema(description = "表情 token（emoji 必填，如 [微笑]）")
            private String alt;
        }
    }

}
