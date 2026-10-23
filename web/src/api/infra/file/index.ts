import { requestClient } from '#/utils/request';
import type { PageParam, PageResult } from '#/utils/request';

export namespace InfraFileApi {
  export interface File {
    id?: number;
    configId: number;
    name: string;
    path: string;
    url: string;
    type: string;
    size: number;
    createTime?: string;
  }
}

/** 查询文件列表 */
export function getFilePage(params: PageParam) {
  return requestClient.get<PageResult<InfraFileApi.File>>('/infra/file/page', { params });
}

/** 删除文件 */
export function deleteFile(id: number) {
  return requestClient.delete(`/infra/file/delete?id=${id}`);
}

/** 上传文件 */
export function uploadFile(data: { file: File; directory?: string }) {
  const formData = new FormData();
  formData.append('file', data.file);
  if (data.directory) {
    formData.append('directory', data.directory);
  }
  return requestClient.post<string>('/infra/file/upload', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
  });
}
