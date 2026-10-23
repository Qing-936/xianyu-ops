import { requestClient } from '#/utils/request';
import type { PageParam, PageResult } from '#/utils/request';

export namespace SystemNoticeApi {
  export interface Notice {
    id?: number;
    title: string;
    type: number;
    content: string;
    status: number;
    createTime?: string;
  }
}

/** 查询通知公告列表 */
export function getNoticePage(params: PageParam) {
  return requestClient.get<PageResult<SystemNoticeApi.Notice>>('/system/notice/page', { params });
}

/** 查询通知公告详情 */
export function getNotice(id: number) {
  return requestClient.get<SystemNoticeApi.Notice>(`/system/notice/get?id=${id}`);
}

/** 新增通知公告 */
export function createNotice(data: SystemNoticeApi.Notice) {
  return requestClient.post('/system/notice/create', data);
}

/** 修改通知公告 */
export function updateNotice(data: SystemNoticeApi.Notice) {
  return requestClient.put('/system/notice/update', data);
}

/** 删除通知公告 */
export function deleteNotice(id: number) {
  return requestClient.delete(`/system/notice/delete?id=${id}`);
}
