import axios from 'axios';
import type {
  AxiosInstance,
  AxiosRequestConfig,
  AxiosResponse,
  InternalAxiosRequestConfig,
} from 'axios';
import { message } from 'ant-design-vue';
import { useAuthStore } from '#/store/auth';
import { useUserStore } from '#/store/user';
import { LOGIN_PATH } from '#/router/routes/core';
import { refreshTokenApi } from '#/api/core/auth';
import { applyTenantHeaders } from '#/utils/tenant';
import { RefreshQueue } from '#/utils/refresh-queue';
import { RefreshRejectedError, classifyRefreshFailure } from '#/utils/auth-recovery';

declare module 'axios' {
  export interface AxiosRequestConfig {
    /** 静默模式（14 号 §5.2）：错误不弹全局 message.error，供轮询类请求使用 */
    __silent?: boolean;
  }
}

/**
 * 接口错误（携带后端 code 的结构化错误）。
 * 供调用方按 code 分支处理，如全局提醒轮询的 403 熔断（14 号 §5.2）；
 * 普通调用方按 Error 使用即可，向后兼容。
 */
export class ApiError extends Error {
  code: number;

  constructor(code: number, message: string) {
    super(message);
    this.name = 'ApiError';
    this.code = code;
  }
}

/** Token 刷新控制 */
let isRefreshing = false;
/** 刷新进行中的等待队列（纯逻辑模块：失败时整体拒绝，避免请求永久等待/空 token 重试） */
const refreshQueue = new RefreshQueue();
/** 单飞中的 refresh-token 调用（并发共享同一个 Promise；Interactive Auth P1 复用） */
let pendingRefreshPromise: Promise<string> | null = null;

/** 后端响应格式 */
export interface ApiResult<T = any> {
  code: number;
  data: T;
  msg: string;
}

/** 分页参数 */
export interface PageParam {
  pageNo: number;
  pageSize: number;
}

/** 分页结果 */
export interface PageResult<T = any> {
  list: T[];
  total: number;
}

const apiURL = import.meta.env.VITE_GLOB_API_URL || '/admin-api';

/** 从 refresh-token 响应中提取新 accessToken（兼容两种包装形式） */
function extractAccessToken(resp: unknown): string {
  const wrapped = resp as any;
  return (
    wrapped?.data?.data?.accessToken ||
    wrapped?.data?.accessToken ||
    wrapped?.accessToken ||
    ''
  );
}

/**
 * 单飞刷新登录态（Interactive Auth P1.1）。
 *
 * - 同一时刻只允许一次 refresh-token 调用：并发调用方共享同一个 Promise（上游 yudao
 *   换发新 access token 会删除旧 token，重复刷新反而会把刚刚换到的 token 弄丢）；
 * - 成功后写入 authStore.accessToken 并返回新 token；
 * - 失败时抛错（调用方决定登出/提示），并自动清空单飞状态以便下次重试。
 *
 * 注意：仅刷新登录态，不发起任何业务请求；业务重放由调用方控制（最多一次）。
 */
export function refreshAccessTokenSingleFlight(): Promise<string> {
  const authStore = useAuthStore();
  const refreshToken = authStore.refreshToken ?? '';
  if (!refreshToken) {
    return Promise.reject(new RefreshRejectedError('当前登录态缺少 refreshToken'));
  }
  if (!pendingRefreshPromise) {
    pendingRefreshPromise = refreshTokenApi(refreshToken)
      .then((resp) => {
        const newAccessToken = extractAccessToken(resp);
        if (!newAccessToken) {
          throw new Error('刷新 token 失败：响应中无 accessToken');
        }
        authStore.accessToken = newAccessToken;
        return newAccessToken;
      })
      .finally(() => {
        pendingRefreshPromise = null;
      });
  }
  return pendingRefreshPromise;
}

/** 创建 Axios 实例 */
function createRequestClient(): AxiosInstance {
  const client = axios.create({
    baseURL: apiURL,
    timeout: 30000,
    // GET 数组参数序列化为重复参数（createTime=a&createTime=b），
    // Spring 后端 LocalDateTime[] 只支持该格式（indexed 格式 createTime[0] 会 400）
    paramsSerializer: { indexes: null },
  });

  /**
   * 401 Token 刷新处理（在成功回调中直接调用，无需经过错误回调）
   * 说明：Axios 拦截器 .then(respFulfill, respReject) 中，
   * respReject 只捕获 dispatchRequest 的失败，不会捕获 respFulfill 的 reject。
   * 因此 401 刷新必须直接在成功回调中处理。
   */
  async function handle401Refresh(
    config: InternalAxiosRequestConfig,
    response: AxiosResponse<ApiResult>,
  ) {
    const authStore = useAuthStore();
    const cfg = config as any;

    // 没有 refreshToken 或已是重试请求，直接登出
    if (!authStore.refreshToken || cfg.__isRetryRequest) {
      if (!cfg.__isRetryRequest) {
        await authStore.logout();
      }
      return Promise.reject(new Error(response.data?.msg || '未授权'));
    }

    // 如果正在刷新 token，加入队列等待（刷新失败 → 队列整体拒绝，请求正常结束；
    // 用户已切换 → 拒绝重试，不把旧用户的操作继续到新用户会话）
    if (isRefreshing) {
      const expectedUserId = useUserStore().userInfo?.id ?? null;
      return refreshQueue.wait().then((newToken) => {
        const currentUserId = useUserStore().userInfo?.id ?? null;
        if (currentUserId !== expectedUserId) {
          throw new Error('登录用户已变化，已取消旧请求重试');
        }
        // 队列等待后的重试同样只允许一次：把重试标记写在发起之前。
        // 缺失该标记时，若本次重试仍 401，会再次“刷新 + 重试”，
        // 使单个请求出现 2 次重放（超出每轮“刷新 1 次 + 重放 1 次”的预算）。
        cfg.__isRetryRequest = true;
        config.headers.Authorization = `Bearer ${newToken}`;
        return client(config);
      });
    }

    // 开始刷新 token
    isRefreshing = true;
    cfg.__isRetryRequest = true;

    try {
      // 复用全局单飞刷新（与 Agent 聊天链路共用同一次 refresh-token 调用）
      const newAccessToken = await refreshAccessTokenSingleFlight();

      // 刷新成功：队列中的请求全部用新 token 重试
      refreshQueue.settleSuccess(newAccessToken);

      // 重试原请求
      config.headers.Authorization = `Bearer ${newAccessToken}`;
      return client(config);
    } catch (refreshError) {
      // 刷新失败：队列整体拒绝（不再用空 token 重试）。
      // 分类：明确拒绝（4xx / 缺 refreshToken）→ 登出；网络/服务暂不可用 → 不登出，可稍后重试
      const classified = classifyRefreshFailure(refreshError);
      refreshQueue.settleFailure(classified);
      if (classified instanceof RefreshRejectedError) {
        await authStore.logout();
      }
      return Promise.reject(classified);
    } finally {
      isRefreshing = false;
    }
  }

  // 请求拦截器
  client.interceptors.request.use(
    (config: InternalAxiosRequestConfig) => {
      const authStore = useAuthStore();

      // 设置 token
      if (authStore.accessToken) {
        config.headers.Authorization = `Bearer ${authStore.accessToken}`;
      }

      // 租户头（tenant-id + visit-tenant-id，多租户开关控制）
      applyTenantHeaders(config.headers);

      return config;
    },
    (error) => Promise.reject(error),
  );

  // 响应拦截器
  client.interceptors.response.use(
    (response: AxiosResponse<ApiResult>) => {
      const { data, config } = response;

      // Blob 响应直接返回
      if (config.responseType === 'blob') {
        return response as any;
      }

      // 业务成功 (code === 0)
      if (data.code === 0) {
        return data.data as any;
      }

      // 401 未授权 — 走 Token 刷新逻辑（直接在成功回调处理）
      if (data.code === 401) {
        return handle401Refresh(config as InternalAxiosRequestConfig, response);
      }

      // 其他业务错误（静默模式跳过全局提示，14 号 §5.2；携带 code 供轮询熔断判定）
      if (!config.__silent) {
        message.error(data.msg || '请求失败');
      }
      return Promise.reject(new ApiError(data.code, data.msg || '请求失败'));
    },
    async (error) => {
      const { config, response, data: responseData } = error;

      // 非 401 错误直接抛
      const is401 =
        response?.status === 401 || responseData?.code === 401;
      if (!is401) {
        const msg =
          responseData?.msg || error.message || '网络错误';
        // 静默模式跳过全局提示（14 号 §5.2）；HTTP 403 携带 code 供轮询熔断
        if (!config?.__silent) {
          message.error(msg);
        }
        if (response?.status === 403) {
          return Promise.reject(new ApiError(403, msg));
        }
        return Promise.reject(error);
      }

      // HTTP 级别的 401 — 也走 Token 刷新逻辑
      if (config && response) {
        return handle401Refresh(config as InternalAxiosRequestConfig, response);
      }

      // 没有 config/response 信息，直接登出
      const authStore = useAuthStore();
      await authStore.logout();
      return Promise.reject(error);
    },
  );

  return client;
}

/** 主请求客户端 — 自动解包 data，返回业务数据 */
export const requestClient = createRequestClient() as Omit<AxiosInstance, 'get' | 'post' | 'put' | 'delete'> & {
  get<T = any>(url: string, config?: AxiosRequestConfig): Promise<T>;
  post<T = any>(url: string, data?: any, config?: AxiosRequestConfig): Promise<T>;
  put<T = any>(url: string, data?: any, config?: AxiosRequestConfig): Promise<T>;
  delete<T = any>(url: string, config?: AxiosRequestConfig): Promise<T>;
};

/** 基础请求客户端 — 返回完整响应 */
export const baseRequestClient = axios.create({
  baseURL: apiURL,
  timeout: 30000,
  paramsSerializer: { indexes: null },
});

// 基础客户端也加租户头
baseRequestClient.interceptors.request.use((config) => {
  const authStore = useAuthStore();
  if (authStore.accessToken) {
    config.headers.Authorization = `Bearer ${authStore.accessToken}`;
  }
  applyTenantHeaders(config.headers);
  return config;
});
