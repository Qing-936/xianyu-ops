package io.github.qing936.xianyuops.module.xianyu.service.agent;

/**
 * 闲鱼 Agent 请求上下文（ThreadLocal）：Filter 校验通过后写入，业务层读取。
 * <p>
 * 两种凭据二选一（M5 一机一 Token + 一次性注册码）：
 * - <b>专属 Token</b>：Filter 用其 SHA-256 精确查 agent 表，命中后写入 agentId，放行全部 /agent-api；
 * - <b>注册码</b>：Filter 用其 SHA-256 查注册码表（未使用 + 未过期），命中后写入 enrollId，
 *   仅放行首次注册心跳，由心跳逻辑签发专属 Token 并消费该码。
 * <p>
 * Filter 必须在 finally 中 {@link #clear()}，防止 Tomcat 线程复用导致串号。
 */
public class XianyuAgentContext {

    private static final ThreadLocal<Entry> HOLDER = new ThreadLocal<>();

    private record Entry(Long agentId, Long enrollId) {
    }

    /** Filter 校验通过后写入：专属 Token → agentId 非空；注册码 → enrollId 非空（两者互斥） */
    public static void set(Long agentId, Long enrollId) {
        HOLDER.set(new Entry(agentId, enrollId));
    }

    /** 请求身份 Agent ID（注册码阶段为 null，此时 Agent 行可能还不存在） */
    public static Long getAgentId() {
        Entry entry = HOLDER.get();
        return entry != null ? entry.agentId() : null;
    }

    /** 本次命中的注册码 ID（非空 = 首次注册心跳；心跳成功后须消费） */
    public static Long getEnrollId() {
        Entry entry = HOLDER.get();
        return entry != null ? entry.enrollId() : null;
    }

    /** Filter finally 调用：清理上下文，防线程池复用串号 */
    public static void clear() {
        HOLDER.remove();
    }

}
