package io.github.qing936.xianyuops.module.xianyu.dal.mysql;

import io.github.qing936.xianyuops.framework.common.pojo.PageResult;
import io.github.qing936.xianyuops.framework.mybatis.core.mapper.BaseMapperX;
import io.github.qing936.xianyuops.framework.mybatis.core.query.LambdaQueryWrapperX;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.replytask.vo.XianyuReplyTaskPageReqVO;
import io.github.qing936.xianyuops.module.xianyu.dal.data.XianyuReplyTaskDO;
import io.github.qing936.xianyuops.module.xianyu.enums.XianyuReplyTaskStatusEnum;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface XianyuReplyTaskMapper extends BaseMapperX<XianyuReplyTaskDO> {

    /**
     * 原子领取：pending → dispatched 条件更新。
     * 影响行数 = 0 说明已被取消/领取，调用方跳过不下发。
     */
    @Update("""
            UPDATE app_xianyu_reply_task
            SET status = 'dispatched', dispatched_at = NOW()
            WHERE id = #{taskId} AND status = 'pending' AND agent_id = #{agentId}
            """)
    int claimPending(@Param("taskId") Long taskId, @Param("agentId") Long agentId);

    /**
     * 取消：pending → canceled 条件更新（M1 评审 P2-2）。
     * 与 claimPending 互斥：若 poll 已把任务领取为 dispatched，本语句影响行数 = 0，调用方拒绝取消，
     * 避免「管理端显示已取消、Agent 却照样发出」。
     */
    @Update("""
            UPDATE app_xianyu_reply_task
            SET status = 'canceled', finished_at = NOW()
            WHERE id = #{taskId} AND status = 'pending'
            """)
    int cancelPending(@Param("taskId") Long taskId);

    /**
     * 超时兜底：dispatched 且超过阈值未回传 → timeout。
     * 与 poll 原子领取互斥：dispatched 期间 poll 不会再次领取，本语句只碰超时行，无竞态。
     */
    @Update("""
            UPDATE app_xianyu_reply_task
            SET status = 'timeout', result_msg = '任务超时未回传', finished_at = NOW()
            WHERE status = 'dispatched' AND dispatched_at < #{beforeTime}
            """)
    int updateTimeoutDispatched(@Param("beforeTime") LocalDateTime beforeTime);

    /**
     * 超时兜底候选 id：dispatched 且超过阈值未回传。
     * <p>
     * ⚠️ 必须在 {@link #updateTimeoutDispatched} **之前**调用：置为 timeout 后就无法再按
     * dispatched 条件筛出「本次超时」的行（会被下一轮的新超时任务混淆）。
     * 调用方拿这批 id 把对应的 out 消息发送状态回填为 unknown。
     */
    default List<Long> selectTimeoutDispatchedIds(LocalDateTime beforeTime) {
        return selectList(new LambdaQueryWrapperX<XianyuReplyTaskDO>()
                .eq(XianyuReplyTaskDO::getStatus, XianyuReplyTaskStatusEnum.DISPATCHED.getStatus())
                .lt(XianyuReplyTaskDO::getDispatchedAt, beforeTime))
                .stream().map(XianyuReplyTaskDO::getId).toList();
    }

    default PageResult<XianyuReplyTaskDO> selectPage(XianyuReplyTaskPageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<XianyuReplyTaskDO>()
                .eqIfPresent(XianyuReplyTaskDO::getStatus, reqVO.getStatus())
                .eqIfPresent(XianyuReplyTaskDO::getAgentId, reqVO.getAgentId())
                .eqIfPresent(XianyuReplyTaskDO::getAccountId, reqVO.getAccountId())
                .orderByDesc(XianyuReplyTaskDO::getId));
    }

}
