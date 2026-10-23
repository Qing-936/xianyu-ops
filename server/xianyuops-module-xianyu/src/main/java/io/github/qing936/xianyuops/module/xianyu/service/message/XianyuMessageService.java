package io.github.qing936.xianyuops.module.xianyu.service.message;

import io.github.qing936.xianyuops.framework.common.pojo.PageResult;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.message.vo.XianyuConversationListReqVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.message.vo.XianyuConversationReadReqVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.message.vo.XianyuConversationRespVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.message.vo.XianyuMessagePageReqVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.message.vo.XianyuMessageRespVO;
import io.github.qing936.xianyuops.module.xianyu.controller.agentapi.vo.UploadReqVO;
import io.github.qing936.xianyuops.module.xianyu.controller.agentapi.vo.UploadRespVO;

import java.util.List;

/**
 * 闲鱼买家消息服务（上报/判重/会话聚合）
 */
public interface XianyuMessageService {

    /**
     * 批量上报消息：逐条校验 + 窗口判重 + 账号对齐，单条失败不中断整批。
     * <p>
     * 调用点须在租户上下文内（Agent 侧 Controller 经 TenantUtils.execute 包裹）。
     *
     * @param agentId  Agent ID（调用方从 token 推导）
     * @param messages 消息列表
     */
    UploadRespVO upload(Long agentId, List<UploadReqVO.MessageItem> messages);

    /** 管理端：消息分页 */
    PageResult<XianyuMessageRespVO> getMessagePage(XianyuMessagePageReqVO reqVO);

    /** 管理端：会话聚合列表（未绑定账号按昵称快照独立成会话） */
    List<XianyuConversationRespVO> getConversationList(XianyuConversationListReqVO reqVO);

    /**
     * 管理端：会话标记已读（打开会话时调用），返回本次置为已读的消息行数。
     * <p>
     * 定位口径与 {@link #getConversationList} 一致：accountId 非空按已对齐账号匹配，
     * 否则按账号昵称快照兜底；只把 in + unread → read，replied 不降级。
     */
    int markConversationRead(XianyuConversationReadReqVO reqVO);

    /**
     * 管理端：全局未读消息总数（全局提醒轮询用，轻量 COUNT，14 号 §5.1）。
     * <p>
     * 口径与 {@link #getConversationList} 的会话未读聚合一致（deleted=0 AND msg_status='unread'），
     * 保证全局总数 = 工作台左栏各会话未读徽标之和。
     */
    Long getUnreadCount();

}
