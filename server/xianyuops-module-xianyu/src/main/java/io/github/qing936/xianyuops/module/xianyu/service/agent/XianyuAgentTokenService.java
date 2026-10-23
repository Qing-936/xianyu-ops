package io.github.qing936.xianyuops.module.xianyu.service.agent;

import io.github.qing936.xianyuops.framework.tenant.core.util.TenantUtils;
import io.github.qing936.xianyuops.module.xianyu.dal.data.XianyuAgentDO;
import io.github.qing936.xianyuops.module.xianyu.dal.mysql.XianyuAgentMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

import static io.github.qing936.xianyuops.framework.common.exception.util.ServiceExceptionUtil.exception;
import static io.github.qing936.xianyuops.module.xianyu.enums.XianyuErrorCodeConstants.XIANYU_AGENT_TOKEN_INVALID;

/**
 * Agent Token 鉴权服务（M5 一机一 Token：token → agentId 精确匹配）。
 * <p>
 * - Filter 调用 {@link #resolveAgentIdByToken(String)}：请求 Token 的 SHA-256 查 agent 表，
 *   命中返回 agentId，未命中返回 null（由 Filter 决定是否走一次性注册码 / 拒绝）；
 * - 业务调用 {@link #resolveAgentId()}：读取 Filter 写入的请求上下文（{@link XianyuAgentContext}），
 *   不再「全表取首条」；
 * - {@link #issueToken(Long)}：签发/重置专属 Token（明文仅本次返回，库中只存 SHA-256）。
 * <p>
 * 注意：{@code resolveAgentIdByToken} 在 Filter 中调用（无租户上下文），内部经
 * {@link TenantUtils} 在配置租户上下文中执行；心跳等业务调用点须在租户包裹内。
 */
@Service
@Slf4j
public class XianyuAgentTokenService {

    /** 专属 Token 明文长度（32 字节 SecureRandom → 64 hex 字符） */
    private static final int TOKEN_BYTES = 32;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    @Resource
    private XianyuAgentMapper agentMapper;

    @Resource
    private XianyuAgentProperties properties;

    /**
     * Filter 调用：专属 Token 精确匹配（sha256 查表）。命中返回 agentId，未命中返回 null。
     * <p>
     * DB 异常不吞（冒泡为 5xx）：避免把服务端故障误判为 Token 无效，导致 Agent 端 401 退出进程。
     */
    public Long resolveAgentIdByToken(String rawToken) {
        String tokenHash = sha256Hex(rawToken);
        XianyuAgentDO agent = TenantUtils.execute(properties.getTenantId(),
                () -> agentMapper.selectByTokenHash(tokenHash));
        return agent != null ? agent.getId() : null;
    }

    /**
     * 业务调用：从请求上下文读取 agentId（Filter 校验专属 Token 后写入）。
     * 上下文为空 = 请求未经专属 Token 激活（理论不可达：注册码仅放行心跳 URI，而心跳不走本方法）。
     */
    public Long resolveAgentId() {
        Long agentId = XianyuAgentContext.getAgentId();
        if (agentId == null) {
            throw exception(XIANYU_AGENT_TOKEN_INVALID);
        }
        return agentId;
    }

    /**
     * 签发/重置专属 Token：生成强随机明文 → sha256 入库 → 返回明文（仅调用方可见一次）。
     * 调用点须在租户上下文内（心跳注册经 TenantUtils 包裹；管理端接口自带登录租户）。
     */
    public String issueToken(Long agentId) {
        String plainToken = generateToken();
        XianyuAgentDO update = new XianyuAgentDO();
        update.setId(agentId);
        update.setTokenHash(sha256Hex(plainToken));
        agentMapper.updateById(update);
        return plainToken;
    }

    /** 生成 64 hex 强随机 Token 明文（32 字节 SecureRandom） */
    public static String generateToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /** SHA-256 → 64 hex（Token 不收明文入库、不打日志） */
    public static String sha256Hex(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

}
