import { requestClient } from '#/utils/request';
import type { PageParam, PageResult } from '#/utils/request';

/** 参数配置响应 */
export interface InfraConfigRespVO {
  id?: number;
  category?: string;
  name?: string;
  key?: string;
  value?: string;
  visible?: boolean;
  remark?: string;
  createTime?: string;
}

/** 参数配置创建/更新请求 */
export interface InfraConfigSaveReqVO {
  id?: number;
  category: string;
  name: string;
  key: string;
  value: string;
  visible: boolean;
  remark?: string;
}

/** 获取参数配置分页 */
export function getConfigPage(params: PageParam & { key?: string }) {
  return requestClient.get<PageResult<InfraConfigRespVO>>('/infra/config/page', { params });
}

/** 根据参数键名查询参数值 */
export function getConfigValueByKey(key: string) {
  return requestClient.get<string | null>('/infra/config/get-value-by-key', { params: { key } });
}

/** 创建参数配置 */
export function createConfig(data: InfraConfigSaveReqVO) {
  return requestClient.post<number>('/infra/config/create', data);
}

/** 更新参数配置 */
export function updateConfig(data: InfraConfigSaveReqVO) {
  return requestClient.put<boolean>('/infra/config/update', data);
}
