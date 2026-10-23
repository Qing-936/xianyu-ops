package io.github.qing936.xianyuops.module.xianyu.service.agent;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 闲鱼机器 Agent 配置属性
 */
@Data
@Component
@ConfigurationProperties(prefix = "xianyuops.xianyu.agent")
public class XianyuAgentProperties {

    /**
     * 所属租户编号（/agent-api 无登录态、无 tenant-id 请求头，通过配置指定，与虚拟货源回调同模式）
     */
    private Long tenantId = 1L;

    /**
     * 一次性注册码有效期（分钟）。管理端生成后须在该窗口内完成首次心跳，超时即失效需重新生成
     */
    private Integer enrollCodeTtlMinutes = 15;

    /**
     * 建议心跳周期（秒），心跳响应 pollIntervalSec 返回给 Agent
     */
    private Integer pollIntervalSec = 15;

    /**
     * 长轮询挂起时长（秒），poll 接口阻塞上限
     */
    private Integer longPollTimeoutSec = 25;

    /**
     * 回复任务超时兜底（分钟），dispatched 超过该时长未回传 → timeout
     */
    private Integer replyTaskTimeoutMinutes = 5;

    /**
     * 消息窗口判重分钟数
     */
    private Integer msgDedupWindowMinutes = 5;

    /**
     * Agent 离线判定阈值（秒），lastHeartbeatAt 超过该时长 → offline
     */
    private Integer offlineThresholdSec = 45;

    /**
     * 媒体外发服务端开关（15 号文档回滚开关）：false 时拒绝 image/emoji 任务，文本不受影响
     */
    private Boolean outboundMediaEnabled = true;

}
