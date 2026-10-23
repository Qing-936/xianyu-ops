/**
 * 登录凭证刷新失败分类 + 受控登录重放循环（纯逻辑模块，供 request / service / adapters 共用）。
 *
 * 预算口径（2026-10-15 第七轮核对收紧）：每轮恢复 = 原请求 1 次 + **刷新 1 次 + 重放 1 次**，
 * 即业务请求**至多 2 次**（不是"首次请求之后再重放两次"）。
 * 后端 `authentication.max_auth_replays=2` 是**声明性上限**（服务端不计数、不拦截），
 * 前端取更保守值；如需调整只看 `MAX_AUTH_REPLAYS` 一处。
 *
 * 其它约束（与后端契约 §17.6 一致）：
 * - 明确刷新被拒（4xx / 缺少 refreshToken）→ **立即停止**，不再重放、不循环；
 * - 刷新接口网络失败 / 5xx → 归为「暂时不可用」：不强制登出，由界面提供显式重试入口；
 * - 登录重放预算只由鉴权类失败驱动，业务错误与传输层错误不进入本循环（预算分离）。
 *
 * 本模块不发起任何请求、不依赖 Vue/Pinia，可独立（Node）执行验证。
 */

/**
 * 每轮恢复允许的登录重放次数（原请求之外）：**1**。
 * 即最坏一轮：原请求 1 次 +（刷新后）重放 1 次 = 业务请求 2 次。
 * 后端声明额度（max_auth_replays=2）不构成必须用满的预算，前端按产品规则收紧到此值。
 */
export const MAX_AUTH_REPLAYS = 1;

/** 刷新登录凭证被服务端明确拒绝（4xx / 缺少 refreshToken）：需要重新登录，禁止自动重试。 */
export class RefreshRejectedError extends Error {
  readonly cause?: unknown;

  constructor(message: string, cause?: unknown) {
    super(message);
    this.name = 'RefreshRejectedError';
    this.cause = cause;
  }
}

/** 刷新登录凭证暂时不可用（网络失败 / 超时 / 5xx）：会话未判死，可稍后手动重试。 */
export class RefreshUnavailableError extends Error {
  readonly cause?: unknown;

  constructor(message: string, cause?: unknown) {
    super(message);
    this.name = 'RefreshUnavailableError';
    this.cause = cause;
  }
}

export type AuthRecoveryKind = 'rejected' | 'unavailable' | 'exhausted';

/**
 * 登录恢复流程的终态错误（交给界面展示/决定后续动作）。
 * - rejected：凭证被明确拒绝 → 由调用方负责登出（清会话）并引导重新登录；
 * - unavailable：刷新接口暂不可用（网络）→ 未登录失效，可稍后由用户显式重试；
 * - exhausted：已用满（每轮 1 次）重放预算仍鉴权失败 → 提示重新登录。
 */
export class AuthRecoveryError extends Error {
  readonly kind: AuthRecoveryKind;

  constructor(kind: AuthRecoveryKind, message: string) {
    super(message);
    this.name = 'AuthRecoveryError';
    this.kind = kind;
  }
}

/**
 * 刷新失败分类（供三链在决定是否登出/是否可手动重试时使用）。
 * 规则：带 `response.status` 且为 4xx → 明确拒绝；其余（网络失败、超时、5xx、未知）→ 暂时不可用。
 */
export function classifyRefreshFailure(error: unknown): RefreshRejectedError | RefreshUnavailableError {
  if (error instanceof RefreshRejectedError || error instanceof RefreshUnavailableError) {
    return error;
  }
  const status = (error as { response?: { status?: unknown } } | null | undefined)?.response?.status;
  if (typeof status === 'number' && status >= 400 && status < 500) {
    return new RefreshRejectedError('刷新登录凭证被拒绝', error);
  }
  return new RefreshUnavailableError('刷新登录凭证失败（网络或服务不可用）', error);
}

export interface AuthReplayLoopOptions<T> {
  /** 单飞刷新（成功即已把最新 token 写入 auth store；失败抛错由本模块分类）。 */
  refresh: () => Promise<void>;
  /** 用最新凭证重放**原请求**（同会话、同请求体、同幂等键、同已见版本）。 */
  replay: () => Promise<T>;
  /** 抛错型鉴权失败判定（如服务的 HTTP 401）。 */
  isAuthFailure?: (error: unknown) => boolean;
  /** 返回值型鉴权失败判定（如 HTTP 200 业务鉴权失败响应）。 */
  isReplayableAuthResponse?: (value: T) => boolean;
  /** 预算覆盖（默认 MAX_AUTH_REPLAYS）。 */
  maxReplays?: number;
}

/**
 * 受控登录重放循环：每次先用最新凭证刷新，再重放原请求；仍鉴权失败则消耗下一次预算。
 *
 * 返回：重放成功后的业务结果；抛错：`AuthRecoveryError`（rejected / unavailable / exhausted）
 * 或重放中的非鉴权错误（原样上抛，不吞错）。
 */
export async function runAuthReplayLoop<T>(options: AuthReplayLoopOptions<T>): Promise<T> {
  const maxReplays = options.maxReplays ?? MAX_AUTH_REPLAYS;
  for (let replay = 1; replay <= maxReplays; replay += 1) {
    try {
      await options.refresh();
    } catch (error) {
      const classified = classifyRefreshFailure(error);
      if (classified instanceof RefreshRejectedError) {
        throw new AuthRecoveryError('rejected', '登录状态已失效，请重新登录');
      }
      throw new AuthRecoveryError(
        'unavailable',
        '登录凭证刷新服务暂时不可用（网络或服务异常），可稍后点击右上角「刷新」重试',
      );
    }
    try {
      const value = await options.replay();
      if (options.isReplayableAuthResponse?.(value)) {
        continue;
      }
      return value;
    } catch (error) {
      if (options.isAuthFailure?.(error)) {
        continue;
      }
      throw error;
    }
  }
  throw new AuthRecoveryError('exhausted', '登录状态已失效，请重新登录');
}
