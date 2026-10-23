package io.github.qing936.xianyuops.module.xianyu.dal.data;

import io.github.qing936.xianyuops.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * 闲鱼买家消息
 * <p>
 * 无永久唯一键：同类型消息按窗口判重（媒体用 ext_json.dedupKey，其余用 content），维度见 Mapper。
 * 注：原 client_msg_id / raw_json 两列为「只写不读」，已于 2026-09-24 随单表瘦身移除——
 * Agent 上报契约字段仍在 UploadReqVO/UploadRespVO，仅不再落库（列已 DROP，勿再加回）。
 */
@TableName("app_xianyu_chat_message")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class XianyuChatMessageDO extends TenantBaseDO {

    @TableId
    private Long id;

    /** 来源 Agent */
    private Long agentId;

    /** 闲鱼账号 ID（对齐失败可空） */
    private Long accountId;

    /** 账号昵称快照（accountKey.userNick；未对齐会话的聚合兜底键与判重维度） */
    private String accountNick;

    /** 买家 ID（OCR 不一定拿得到，可空） */
    private String buyerId;

    /** 买家昵称（会话键，必填） */
    private String buyerNick;

    /** 会话标识（会话唯一 id，= 会话行 sessionInfo.sessionId；可空=未采到，此时聚合回退 buyer_nick） */
    private String chatId;

    /** 会话绑定闲鱼商品 ID（优先取会话行 sessionInfo.itemInfo.itemId，商品卡推断为兜底；可空=未关联商品会话） */
    private Long itemId;

    /** 方向：in（收到）/ out（发出，任务成功时 Java 补录），见 {@link io.github.qing936.xianyuops.module.xianyu.enums.XianyuMessageDirectionEnum} */
    private String direction;

    /** 类型：text/image/emoji/audio/video/order/card/other，见 {@link io.github.qing936.xianyuops.module.xianyu.enums.XianyuContentTypeEnum} */
    private String contentType;

    /** 消息内容 */
    private String content;

    /** 关联回复任务 ID（仅 out 消息：创建任务即落库，按此列回填发送结果） */
    private Long taskId;

    /** 闲鱼侧消息时间（OCR 估算） */
    private LocalDateTime receiveTime;

    /** 状态：unread/read/replied，见 {@link io.github.qing936.xianyuops.module.xianyu.enums.XianyuMsgStatusEnum}（out 消息恒为 read） */
    private String msgStatus;

    /**
     * 发送状态（仅 out）：sending/sent/failed/unknown，见 {@link io.github.qing936.xianyuops.module.xianyu.enums.XianyuSendStatusEnum}。
     * NULL = 存量已发出消息（本字段上线前落库的 out 行），前端按「已发送」静默展示。
     */
    private String sendStatus;

    /** 发送失败/结果未知原因（仅 out，冗余自 reply_task.result_msg，避免聊天历史二次查任务表） */
    private String failReason;

    /** 结构化快照：商品卡 ext 或媒体 type=media/sourceMessageId/items/dedupKey；媒体字段经服务端白名单规范化 */
    private String extJson;

    /** 会话级商品快照-主图 URL（agent 每条消息都带：取会话行右侧商品缩略图；item_id 为 NULL 的会话也能展示） */
    private String productImageUrl;

    /** 会话级商品快照-展示价文本（如 ¥1.00；取会话内最近一张商品卡价格，头部展示） */
    private String productPriceText;

    /** 会话级商品快照-商品标题（取会话行 sessionInfo.itemInfo.title；目录命中时优先目录标题） */
    private String productTitle;

}
