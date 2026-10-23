import { requestClient } from '#/utils/request';
import type { PageParam, PageResult } from '#/utils/request';

export namespace SystemLoginLogApi {
  export interface LoginLog {
    id?: number;
    traceId: string;
    logType: number;
    userId: number;
    userType: number;
    username: string;
    result: number;
    userIp: string;
    userAgent: string;
    createTime?: string;
  }

  /** 分页查询参数，与后端 LoginLogPageReqVO 对齐 */
  export interface LoginLogPageParams extends PageParam {
    userIp?: string;
    username?: string;
    /** true=仅成功 false=仅失败 */
    status?: boolean;
    createTime?: string[];
  }
}

/** 查询登录日志列表 */
export function getLoginLogPage(params: SystemLoginLogApi.LoginLogPageParams) {
  return requestClient.get<PageResult<SystemLoginLogApi.LoginLog>>('/system/login-log/page', { params });
}
