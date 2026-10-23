import { requestClient } from '#/utils/request';

/** 服务配置 - 服务 */
export interface ConfigServiceRespVO {
  serviceCode: string;
  serviceName: string;
  entryDesc: string;
  icon: string;
  fileCount: number;
  enabled: boolean;
}

/** 服务配置 - 配置文件（列表项） */
export interface ConfigFileRespVO {
  serviceCode: string;
  fileName: string;
  tag: string;
  description: string;
  modified: boolean;
  updateTime: string;
}

/** 服务配置 - 配置文件详情 */
export interface ConfigFileDetailRespVO extends ConfigFileRespVO {
  content: string;
}

/** 保存配置文件请求 */
export interface ConfigFileSaveReqVO {
  serviceCode: string;
  fileName: string;
  content: string;
}

export const getConfigServices = () => requestClient.get<ConfigServiceRespVO[]>('/app/config/services');

export const getConfigFiles = (serviceCode: string) =>
  requestClient.get<ConfigFileRespVO[]>('/app/config/files', { params: { serviceCode } });

export const getConfigFile = (serviceCode: string, fileName: string) =>
  requestClient.get<ConfigFileDetailRespVO>('/app/config/file', { params: { serviceCode, fileName } });

export const saveConfigFile = (data: ConfigFileSaveReqVO) =>
  // 静默：编译错误可能是多行（带行号），由页面内的错误区展示，避免全局 toast 挤压
  requestClient.put<boolean>('/app/config/file', data, { __silent: true });

export const resetConfigFile = (serviceCode: string, fileName: string) =>
  requestClient.post<boolean>('/app/config/file/reset', null, { params: { serviceCode, fileName } });
