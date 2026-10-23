package io.github.qing936.xianyuops.module.xianyu.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 闲鱼消息内容类型枚举
 * <p>
 * 对应 app_xianyu_chat_message.content_type 字段
 */
@Getter
@AllArgsConstructor
public enum XianyuContentTypeEnum {

    TEXT("text", "文本"),
    IMAGE("image", "图片"),
    EMOJI("emoji", "表情"),
    AUDIO("audio", "语音"),
    VIDEO("video", "视频"),
    ORDER("order", "订单"),
    CARD("card", "卡片"),
    OTHER("other", "其他");

    private final String type;
    private final String desc;

    public boolean isMedia() {
        return this == IMAGE || this == EMOJI || this == AUDIO || this == VIDEO;
    }

    /** v1 外发白名单：voice/video 等媒体暂不支持出站（15 号文档）。 */
    public boolean isOutboundSupported() {
        return this == TEXT || this == IMAGE || this == EMOJI;
    }

    public static XianyuContentTypeEnum valueOfType(String type) {
        if (type == null) {
            return null;
        }
        for (XianyuContentTypeEnum e : values()) {
            if (e.type.equals(type)) {
                return e;
            }
        }
        return null;
    }
}
