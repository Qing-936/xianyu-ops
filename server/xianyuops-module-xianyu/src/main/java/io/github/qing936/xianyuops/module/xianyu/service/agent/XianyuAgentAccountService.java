package io.github.qing936.xianyuops.module.xianyu.service.agent;

import io.github.qing936.xianyuops.module.xianyu.controller.agentapi.vo.HeartbeatReqVO;

import java.util.List;

/**
 * 闲鱼 Agent × 闲鱼号账号对齐
 */
public interface XianyuAgentAccountService {

    /**
     * 账号主数据对齐：authorizeId 非空按 authorize_id 查，否则按 user_nick 查。
     *
     * @return 主数据 accountId；未对齐返回 null
     */
    Long resolveAccountId(String authorizeId, String userNick);

    /**
     * 单个账号快照对齐（§6.3 流程：对齐主数据 → 更新/建绑定行）。
     *
     * @param agentId  Agent ID
     * @param snapshot 心跳上报的账号快照
     */
    void align(Long agentId, HeartbeatReqVO.AccountItem snapshot);

    /**
     * 整批对齐 + 历史行收尾：本次心跳未上报的绑定行 login_status 置 unknown（防状态假在线）。
     */
    void alignAll(Long agentId, List<HeartbeatReqVO.AccountItem> snapshots);

}
