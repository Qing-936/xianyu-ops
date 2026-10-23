import { requestClient } from '#/utils/request';
import type { PageParam, PageResult } from '#/utils/request';

export namespace InfraJobApi {
  export interface JobPageReqVO extends PageParam {
    name?: string;
    handlerName?: string;
    status?: number;
  }

  export interface Job {
    id?: number;
    name: string;
    group: string;
    handlerName: string;
    handlerParam: string;
    cronExpression: string;
    retryCount: number;
    retryInterval: number;
    monitorTimeout: number;
    status: number;
    createTime?: string;
  }
}

/** 查询定时任务列表 */
export function getJobPage(params: InfraJobApi.JobPageReqVO) {
  return requestClient.get<PageResult<InfraJobApi.Job>>('/infra/job/page', { params });
}

/** 查询定时任务详情 */
export function getJob(id: number) {
  return requestClient.get<InfraJobApi.Job>(`/infra/job/get?id=${id}`);
}

/** 新增定时任务 */
export function createJob(data: InfraJobApi.Job) {
  return requestClient.post('/infra/job/create', data);
}

/** 修改定时任务 */
export function updateJob(data: InfraJobApi.Job) {
  return requestClient.put('/infra/job/update', data);
}

/** 删除定时任务 */
export function deleteJob(id: number) {
  return requestClient.delete(`/infra/job/delete?id=${id}`);
}

/** 立即执行任务 */
export function triggerJob(id: number) {
  return requestClient.put(`/infra/job/trigger?id=${id}`);
}

/** 更新任务状态 */
export function updateJobStatus(id: number, status: number) {
  return requestClient.put('/infra/job/update-status', undefined, {
    params: { id, status },
  });
}

/** 查询定时任务日志列表 */
export function getJobLogPage(params: PageParam) {
  return requestClient.get('/infra/job-log/page', { params });
}
