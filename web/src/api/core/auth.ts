import { requestClient, baseRequestClient } from '#/utils/request';
import type { UserInfo } from '#/store/user';

export namespace AuthApi {
  /** 登录接口参数 */
  export interface LoginParams {
    password?: string;
    username?: string;
    captchaVerification?: string;
  }

  /** 登录接口返回值 */
  export interface LoginResult {
    accessToken: string;
    refreshToken: string;
    userId: number;
    expiresTime: number;
  }

  /** 权限信息 */
  export interface AuthPermissionInfo {
    user: UserInfo;
    roles: string[];
    permissions: string[];
    menus: any[];
  }
}

/** 登录 */
export async function loginApi(data: AuthApi.LoginParams) {
  return requestClient.post<AuthApi.LoginResult>('/system/auth/login', data);
}

/** 刷新 accessToken */
export async function refreshTokenApi(refreshToken: string) {
  return baseRequestClient.post(
    `/system/auth/refresh-token?refreshToken=${refreshToken}`,
  );
}

/** 退出登录 */
export async function logoutApi(accessToken: string) {
  return baseRequestClient.post('/system/auth/logout', {}, {
    headers: { Authorization: `Bearer ${accessToken}` },
  });
}

/** 获取权限信息 */
export async function getAuthPermissionInfoApi() {
  return requestClient.get<AuthApi.AuthPermissionInfo>(
    '/system/auth/get-permission-info',
  );
}
