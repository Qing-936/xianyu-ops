import { requestClient } from '#/utils/request';
import type { PageParam, PageResult } from '#/utils/request';

export namespace SystemOperateLogApi {
  /** 与后端 OperateLogRespVO 对齐 */
  export interface OperateLog {
    id?: number;
    traceId: string;
    userId: number;
    /** 操作人昵称（后端 @Trans 自动转译） */
    userName: string;
    userType: number;
    /** 操作模块，如「订单」 */
    type: string;
    /** 操作名，如「创建订单」 */
    subType: string;
    /** 操作模块业务编号 */
    bizId: number;
    /** 操作明细 */
    action: string;
    /** 拓展字段 */
    extra: string;
    requestMethod: string;
    requestUrl: string;
    userIp: string;
    userAgent: string;
    createTime?: string;
  }

  /** 分页查询参数，与后端 OperateLogPageReqVO 对齐 */
  export interface OperateLogPageParams extends PageParam {
    userId?: number;
    bizId?: number;
    type?: string;
    subType?: string;
    action?: string;
    createTime?: string[];
  }
}

/** 查询操作日志列表 */
export function getOperateLogPage(params: SystemOperateLogApi.OperateLogPageParams) {
  return requestClient.get<PageResult<SystemOperateLogApi.OperateLog>>('/system/operate-log/page', { params });
}
