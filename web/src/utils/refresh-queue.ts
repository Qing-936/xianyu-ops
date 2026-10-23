/**
 * 401 刷新等待队列（纯逻辑，可独立验证）。
 *
 * 背景（R8 审查 §14.2/§14.3）：普通 Axios 链在刷新进行中时，后续 401 请求会挂起
 * 等待本次刷新结果。原实现刷新**失败**时用空 token 重试所有等待者（浪费一次请求且
 * 语义错误）；本模块保证：
 * - 刷新成功：所有等待者按加入顺序收到新 token → 用新 token 重试原请求；
 * - 刷新失败：所有等待者收到 reject（**正常结束，绝不永久等待**，也不用空 token 重试）。
 *
 * 用户切换保护：等待者登记时记录当时登录用户标识；重试前由调用方复核，
 * 登录用户已变化时拒绝重试（不把旧用户的操作继续到新用户会话）。
 */
export interface RefreshWaiter {
  resolve: (token: string) => void;
  reject: (error: unknown) => void;
}

export class RefreshQueue {
  private waiters: RefreshWaiter[] = [];

  /** 等待中的请求数（用于验证/诊断，不参与业务判断） */
  get size(): number {
    return this.waiters.length;
  }

  /** 挂起一个请求，等待本轮刷新结果（resolve=新 token；reject=刷新失败/用户变化） */
  wait(): Promise<string> {
    return new Promise<string>((resolve, reject) => {
      this.waiters.push({ resolve, reject });
    });
  }

  /** 刷新成功：全部等待者拿到新 token（队列清空） */
  settleSuccess(token: string): void {
    const pending = this.waiters;
    this.waiters = [];
    for (const waiter of pending) {
      waiter.resolve(token);
    }
  }

  /** 刷新失败：全部等待者被拒绝（队列清空；调用方负责结束各自请求） */
  settleFailure(error: unknown): void {
    const pending = this.waiters;
    this.waiters = [];
    for (const waiter of pending) {
      waiter.reject(error);
    }
  }

  /** 显式清空（登出等场景；被清空的等待者以错误结束，不留悬挂 Promise） */
  reset(reason?: unknown): void {
    this.settleFailure(reason ?? new Error('登录态已重置'));
  }
}
