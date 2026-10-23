package io.github.qing936.xianyuops.module.xianyu.service.agent;

import io.github.qing936.xianyuops.module.xianyu.controller.agentapi.vo.EventReqVO;

/**
 * 闲鱼 Agent 运行事件记录
 */
public interface XianyuAgentEventService {

    /**
     * 记录一条 Agent 运行事件（纯记录，无状态副作用）。
     *
     * @param agentId Agent ID（调用方从 token 推导）
     * @param reqVO   事件内容（accountKey 有则尝试对齐出 account_id）
     */
    void report(Long agentId, EventReqVO reqVO);

}
