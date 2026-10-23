package io.github.qing936.xianyuops.module.xianyu.job;

import io.github.qing936.xianyuops.framework.quartz.core.handler.JobHandler;
import io.github.qing936.xianyuops.framework.tenant.core.job.TenantJob;
import io.github.qing936.xianyuops.module.xianyu.dal.data.XianyuChatMessageDO;
import io.github.qing936.xianyuops.module.xianyu.dal.mysql.XianyuAgentMapper;
import io.github.qing936.xianyuops.module.xianyu.dal.mysql.XianyuChatMessageMapper;
import io.github.qing936.xianyuops.module.xianyu.dal.mysql.XianyuReplyTaskMapper;
import io.github.qing936.xianyuops.module.xianyu.enums.XianyuSendStatusEnum;
import io.github.qing936.xianyuops.module.xianyu.service.agent.XianyuAgentProperties;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 闲鱼 Agent 维护 Job（02 号文档 §8，bean 名与 infra_job 注册一致）
 * <p>
 * 每分钟执行（cron `0 * * * * ?`，种子 SQL 注册）：
 * <ul>
 *   <li>① Agent 离线判定：online 且 lastHeartbeatAt 超过 offline-threshold-sec → offline</li>
 *   <li>② 回复任务超时兜底：dispatched 且 dispatchedAt 超过 reply-task-timeout-minutes → timeout
 *       （与 poll 原子领取互斥：dispatched 期间 poll 不会再次领取，本更新只碰超时行，无竞态）</li>
 *   <li>③ out 消息发送状态兜底：超时任务的 out 消息 → send_status=unknown（结果未知，非失败）</li>
 * </ul>
 * <p>
 * 注意：种子 SQL 中 status=2（停用态），执行后需到「基础设施 → 定时任务」界面手动开启（评审 P2-5）。
 */
@Component("xianyuAgentMaintenanceJob")
@Slf4j
public class XianyuAgentMaintenanceJob implements JobHandler {

    @Resource
    private XianyuAgentMapper agentMapper;

    @Resource
    private XianyuReplyTaskMapper replyTaskMapper;

    @Resource
    private XianyuChatMessageMapper messageMapper;

    @Resource
    private XianyuAgentProperties properties;

    @Override
    @TenantJob // 逐租户执行：租户上下文内 SQL 自动过滤 tenant_id
    public String execute(String param) {
        // ① Agent 离线判定
        LocalDateTime offlineBefore = LocalDateTime.now().minusSeconds(properties.getOfflineThresholdSec());
        int offlineCount = agentMapper.updateOffline(offlineBefore);
        // ② 回复任务超时兜底：先取候选 id（置 timeout 后即无法再按 dispatched 条件筛出本次超时的行）
        LocalDateTime timeoutBefore = LocalDateTime.now().minusMinutes(properties.getReplyTaskTimeoutMinutes());
        List<Long> timeoutTaskIds = replyTaskMapper.selectTimeoutDispatchedIds(timeoutBefore);
        int timeoutCount = replyTaskMapper.updateTimeoutDispatched(timeoutBefore);
        // ③ out 消息发送状态兜底：发送中 → 结果未知（聊天历史内该气泡不会永远停在「发送中」）
        int unknownCount = fillTimeoutMessageStatus(timeoutTaskIds);
        log.info("[xianyuAgentMaintenanceJob][维护完成 置离线={} 置超时={} 消息置未知={}]",
                offlineCount, timeoutCount, unknownCount);
        return String.format("Agent 维护完成：置离线=%d 置超时=%d 消息置未知=%d",
                offlineCount, timeoutCount, unknownCount);
    }

    /**
     * 超时任务的 out 消息回填为「结果未知」——不是失败：超时仅代表 Agent 未回传，
     * 消息可能实际已发出（标记失败会诱导用户重复发送）。
     * <p>
     * 幂等安全：条件含 send_status='sending'——若任务在查询与回填之间被 Agent 正常回传
     * （消息已 sent/failed），本更新不会命中该行，不会覆盖正确结果。
     */
    private int fillTimeoutMessageStatus(List<Long> timeoutTaskIds) {
        if (timeoutTaskIds.isEmpty()) {
            return 0;
        }
        return messageMapper.update(null, new LambdaUpdateWrapper<XianyuChatMessageDO>()
                .in(XianyuChatMessageDO::getTaskId, timeoutTaskIds)
                .eq(XianyuChatMessageDO::getSendStatus, XianyuSendStatusEnum.SENDING.getStatus())
                .set(XianyuChatMessageDO::getSendStatus, XianyuSendStatusEnum.UNKNOWN.getStatus())
                .set(XianyuChatMessageDO::getFailReason, "任务超时未回传（结果未知，请人工核实）"));
    }

}
