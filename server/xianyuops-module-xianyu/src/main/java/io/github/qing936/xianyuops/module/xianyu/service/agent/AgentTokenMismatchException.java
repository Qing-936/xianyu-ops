package io.github.qing936.xianyuops.module.xianyu.service.agent;

/**
 * 心跳身份校验失败（M5 一机一 Token）。
 * <p>
 * 两类场景：
 * 1. 已激活专属 Token 的 Agent 再用一次性注册码心跳（重发 Token 应走管理端「重置」）；
 * 2. 专属 Token 与其携带的机器指纹不符（克隆机未重置 .agent_token / 配置串号）。
 * <p>
 * Controller 捕获后按 401 语义返回（与 Filter 一致），Agent 端按 03 决策 D-03-6
 * 记日志后退出进程，由运维人工处置（重置 Token / 清理克隆机残留后重试）。
 */
public class AgentTokenMismatchException extends RuntimeException {

    public AgentTokenMismatchException(String message) {
        super(message);
    }

}
