package io.github.qing936.xianyuops.module.xianyu.service.replytask;

import io.github.qing936.xianyuops.framework.common.pojo.PageResult;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.replytask.vo.XianyuReplyTaskCreateReqVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.replytask.vo.XianyuReplyTaskPageReqVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.replytask.vo.XianyuReplyTaskRespVO;
import io.github.qing936.xianyuops.module.xianyu.controller.agentapi.vo.PollRespVO;
import io.github.qing936.xianyuops.module.xianyu.controller.agentapi.vo.ResultReqVO;

/**
 * 闲鱼回复任务状态机（唯一外发通道）
 *
 * <pre>
 * pending ──(poll 原子领取)──▶ dispatched ──(回传 success/failed)──▶ success / failed
 *    │                             │
 *    └──(管理端 cancel)──▶ canceled └──(超时无回传，Job 兜底)──▶ timeout
 * </pre>
 */
public interface XianyuReplyTaskService {

    /**
     * 创建回复任务（外发闸门：账号绑定 → Agent 在线 → 账号登录 → 内容校验）。
     *
     * @param userId 创建人（管理端用户 ID）
     * @return 任务 ID
     */
    Long create(XianyuReplyTaskCreateReqVO reqVO, Long userId);

    /**
     * 长轮询拉取任务：循环查 pending → 原子领取（claimPending），领取成功的纳入返回；
     * 无任务 sleep 1s，总挂起 ≤ long-poll-timeout-sec。
     * <p>
     * 调用点须在租户上下文内（Agent 侧 Controller 经 TenantUtils.execute 包裹）。
     */
    PollRespVO pollTasks(Long agentId);

    /**
     * 任务结果回传：校验任务归属与 dispatched 状态；success 时补录 out 消息 + 置原消息 replied。
     * <p>
     * 调用点须在租户上下文内（Agent 侧 Controller 经 TenantUtils.execute 包裹）。
     *
     * @param agentId Agent ID（调用方从 token 推导，须与任务一致）
     */
    void reportResult(Long taskId, Long agentId, ResultReqVO reqVO);

    /** 管理端：取消 pending 任务 → canceled（仅 pending 可取消）；同时把 out 消息回填为失败（未发送） */
    void cancel(Long taskId);

    /**
     * 管理端：查询单个任务（发送状态轮询用，避免拉整页再前端过滤）。
     *
     * @return 任务；不存在返回 null
     */
    XianyuReplyTaskRespVO getTask(Long taskId);

    /**
     * 管理端：失败任务重试 —— 复制原任务关键字段走同一套外发闸门新建任务，
     * 并把原 out 消息行（**复用同一条气泡**）重置为 sending 并改绑新任务。
     *
     * @param userId 操作人（管理端用户 ID）
     * @return 新任务 ID
     */
    Long retry(Long taskId, Long userId);

    /** 管理端：任务分页 */
    PageResult<XianyuReplyTaskRespVO> getTaskPage(XianyuReplyTaskPageReqVO reqVO);

}
