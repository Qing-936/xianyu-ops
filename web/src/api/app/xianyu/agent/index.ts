import { requestClient } from '#/utils/request';
import type { PageParam, PageResult } from '#/utils/request';

/**
 * 闲鱼机器 Agent 管理端 API（02 号文档 §5.1）
 * 与 AI Agent actor 区分：本文件对应「本机跑闲管家的机器 Agent」实例管理。
 */
export namespace XianyuMachineAgentApi {
  export interface MachineAgent {
    id?: number;
    name?: string;
    hostName?: string;
    /** online / offline */
    status?: string;
    /** 最近心跳是否锁屏（锁屏 = OCR 不可用，红色告警） */
    screenLocked?: boolean;
    agentVersion?: string;
    lastHeartbeatAt?: string;
    /** 绑定闲鱼号数量（含未对齐行） */
    accountCount?: number;
    remark?: string;
    /** 是否已激活专属 Token（M5 一机一 Token；false = 尚未完成首次注册领 Token） */
    tokenActivated?: boolean;
  }

  export interface AgentPageReqVO extends PageParam {
    /** Agent 名称，模糊匹配 */
    name?: string;
    /** online / offline */
    status?: string;
  }

  export interface AgentAccount {
    /** 闲鱼账号 ID（未对齐为 null，前端显示「未绑定」告警） */
    accountId?: number | null;
    authorizeId?: string;
    userNick?: string;
    /** 店铺名称（join app_xianyu_account，未绑定为 null） */
    shopName?: string;
    /** online / offline / unknown */
    loginStatus?: string;
    lastReportAt?: string;
    /** 是否已绑定主数据（accountId != null） */
    bound?: boolean;
  }

  export interface AgentEvent {
    id?: number;
    eventType?: string;
    /** info / warn / error */
    level?: string;
    content?: string;
    createTime?: string;
  }

  export interface AgentEventPageReqVO extends PageParam {
    eventType?: string;
    level?: string;
  }

  /** 一次性注册码（列表项不含明文与哈希） */
  export interface AgentEnroll {
    id?: number;
    remark?: string;
    /** 0 待使用 / 1 已使用 / 2 已过期 */
    status?: number;
    expireTime?: string;
    usedTime?: string;
    /** 消费该码注册的 Agent 编号（未使用为 null） */
    agentId?: number | null;
    agentName?: string;
    createTime?: string;
  }

  export interface AgentEnrollPageReqVO extends PageParam {
    remark?: string;
    /** 0 待使用 / 1 已使用 / 2 已过期 */
    status?: number;
  }

  /** 注册码明文仅本响应返回一次，关闭弹窗后无法再查看 */
  export interface AgentEnrollCreated {
    id?: number;
    code?: string;
    remark?: string;
    expireTime?: string;
  }

  export interface AgentEnrollCreateReqVO {
    /** 建议填目标机器名，便于在列表里认出这张码是给哪台机的 */
    remark?: string;
  }
}

export const getXianyuMachineAgentPage = (params: XianyuMachineAgentApi.AgentPageReqVO) =>
  requestClient.get<PageResult<XianyuMachineAgentApi.MachineAgent>>('/app/xianyu/agent/page', { params });

export const getXianyuMachineAgentAccounts = (agentId: number) =>
  requestClient.get<XianyuMachineAgentApi.AgentAccount[]>(`/app/xianyu/agent/${agentId}/accounts`);

export const getXianyuMachineAgentEvents = (agentId: number, params: XianyuMachineAgentApi.AgentEventPageReqVO) =>
  requestClient.get<PageResult<XianyuMachineAgentApi.AgentEvent>>(`/app/xianyu/agent/${agentId}/events`, { params });

/** 管理端：重置专属 Token（返回新明文仅显示一次；旧 Token 立即失效，需人工更新到机器 .agent_token 后重启 Agent） */
export const resetXianyuMachineAgentToken = (agentId: number) =>
  requestClient.put<string>(`/app/xianyu/agent/${agentId}/token/reset`);

/** 管理端：生成一次性注册码（新机器接入用；明文仅本次返回，短时效 + 单次消费） */
export const createXianyuAgentEnrollCode = (data: XianyuMachineAgentApi.AgentEnrollCreateReqVO) =>
  requestClient.post<XianyuMachineAgentApi.AgentEnrollCreated>('/app/xianyu/agent/enroll/create', data);

export const getXianyuAgentEnrollPage = (params: XianyuMachineAgentApi.AgentEnrollPageReqVO) =>
  requestClient.get<PageResult<XianyuMachineAgentApi.AgentEnroll>>('/app/xianyu/agent/enroll/page', { params });

/** 管理端：撤销注册码（立即失效；不影响已签发的专属 Token） */
export const revokeXianyuAgentEnrollCode = (id: number) =>
  requestClient.delete<boolean>(`/app/xianyu/agent/enroll/${id}`);
