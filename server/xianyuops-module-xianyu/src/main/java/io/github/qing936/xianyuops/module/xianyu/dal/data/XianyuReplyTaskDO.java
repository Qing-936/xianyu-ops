package io.github.qing936.xianyuops.module.xianyu.dal.data;

import io.github.qing936.xianyuops.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * 闲鱼回复任务（唯一外发通道）
 */
@TableName("app_xianyu_reply_task")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class XianyuReplyTaskDO extends TenantBaseDO {

    @TableId
    private Long id;

    /** 目标 Agent */
    private Long agentId;

    /** 目标闲鱼账号 */
    private Long accountId;

    /** 目标买家昵称 */
    private String buyerNick;

    /** 回复哪条消息（可空 = 主动发起会话） */
    private Long messageId;

    /** 会话绑定闲鱼商品 ID（回复定位弱校验入参，可空=未绑定商品会话不传；13 号 §4.3） */
    private Long itemId;

    /**
     * 会话唯一标识（= 会话行 sessionInfo.sessionId，可空=未采到）。
     * 创建时由管理端随会话下发，out 消息落库时写入 chat_id 与其同源，
     * 否则 out 行 chat_id 为空会掉进 'noncid:' 分组、与 in 消息的会话分裂成两行。
     */
    private String chatId;

    /** 回复内容（媒体任务为占位文本） */
    private String content;

    /** 内容类型：text/image/emoji，见 {@link io.github.qing936.xianyuops.module.xianyu.enums.XianyuContentTypeEnum} */
    private String contentType;

    /** 媒体快照（type=media + items，与入站 ext_json 同契约；text 为空） */
    private String extJson;

    /** 来源：manual（当前阶段）/ ai（预留），见 {@link io.github.qing936.xianyuops.module.xianyu.enums.XianyuReplyTaskSourceEnum} */
    private String source;

    /** 状态：pending/dispatched/success/failed/canceled/timeout，见 {@link io.github.qing936.xianyuops.module.xianyu.enums.XianyuReplyTaskStatusEnum} */
    private String status;

    /** 执行结果说明（失败原因） */
    private String resultMsg;

    /** 发送后验证截图 URL（OSS） */
    private String verifyImageUrl;

    /** 创建人（管理端用户 ID） */
    private Long createdBy;

    /** 下发时间（Agent 拉取） */
    private LocalDateTime dispatchedAt;

    /** 完成时间 */
    private LocalDateTime finishedAt;

}
