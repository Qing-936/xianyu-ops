package io.github.qing936.xianyuops.module.xianyu.framework.security;

import io.github.qing936.xianyuops.framework.common.pojo.CommonResult;
import io.github.qing936.xianyuops.framework.common.util.servlet.ServletUtils;
import io.github.qing936.xianyuops.module.xianyu.service.agent.XianyuAgentContext;
import io.github.qing936.xianyuops.module.xianyu.service.agent.XianyuAgentEnrollService;
import io.github.qing936.xianyuops.module.xianyu.service.agent.XianyuAgentTokenService;
import jakarta.annotation.Resource;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 闲鱼机器 Agent Token 鉴权过滤器（M5 一机一 Token + 一次性注册码）
 * <p>
 * 拦截所有 /agent-api/ 请求，按以下顺序判定（02 号文档 §7.2）：
 * 1. 专属 Token：请求凭据的 SHA-256 精确查 agent 表，命中 → 请求上下文写入 agentId 后放行；
 * 2. 一次性注册码：SHA-256 查注册码表（未使用 + 未过期），命中 → 仅放行「首次注册心跳」
 *    （POST /agent-api/xianyu/agent/heartbeat），其余 URI 一律拒绝；
 * 3. 均不匹配 → 401。
 * <p>
 * 已无「引导 Token」这类全局长期密钥：新机器接入改由管理端生成一次性注册码（短时效 + 单次消费），
 * 泄露面从整个机群收敛到一台机的一个 TTL 窗口。
 * <p>
 * 注意：不能继承 {@link io.github.qing936.xianyuops.framework.web.core.filter.ApiRequestFilter}——
 * 其 shouldNotFilter 只放行 admin/app 前缀，/agent-api 会被跳过导致校验形同虚设（01 号文档 §10 决策）。
 */
@Component
@Order(-99) // Spring Security Filter 默认 -100 之后（先过 Security 的 permitAll），框架 TenantSecurityFilter(-99) 同序
@Slf4j
public class XianyuAgentTokenFilter extends OncePerRequestFilter {

    /**
     * Agent 鉴权请求头（承载专属 Token 或一次性注册码）
     */
    public static final String TOKEN_HEADER = "X-Agent-Token";

    /** 注册码唯一放行的 URI（首次注册心跳） */
    private static final String HEARTBEAT_URI = "/agent-api/xianyu/agent/heartbeat";

    @Resource
    private XianyuAgentTokenService tokenService;

    @Resource
    private XianyuAgentEnrollService enrollService;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 只过滤 /agent-api/ 前缀的请求，不影响 /admin-api、/app-api 等
        return !request.getRequestURI().startsWith("/agent-api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String credential = request.getHeader(TOKEN_HEADER);
        if (!StringUtils.hasText(credential)) {
            writeUnauthorized(response);
            return;
        }
        // ① 专属 Token：精确匹配（sha256 → agent 表）
        Long agentId = tokenService.resolveAgentIdByToken(credential);
        if (agentId != null) {
            filterWithContext(agentId, null, request, response, filterChain);
            return;
        }
        // ② 一次性注册码：仅放行首次注册心跳
        Long enrollId = enrollService.resolveUsableId(credential);
        if (enrollId != null) {
            if (HEARTBEAT_URI.equals(request.getRequestURI()) && "POST".equalsIgnoreCase(request.getMethod())) {
                filterWithContext(null, enrollId, request, response, filterChain);
                return;
            }
            log.warn("[xianyu-agent] 注册码仅用于首次注册心跳，拒绝请求: uri={}", request.getRequestURI());
            writeUnauthorized(response);
            return;
        }
        // ③ 均不匹配（含注册码已使用/已过期/已撤销）
        log.warn("[xianyu-agent] 凭据校验失败，拒绝请求: uri={}", request.getRequestURI());
        writeUnauthorized(response);
    }

    /** 写入请求上下文后执行过滤链，finally 清理（防 Tomcat 线程复用串号） */
    private void filterWithContext(Long agentId, Long enrollId, HttpServletRequest request,
                                   HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        XianyuAgentContext.set(agentId, enrollId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            XianyuAgentContext.clear();
        }
    }

    private void writeUnauthorized(HttpServletResponse response) {
        ServletUtils.writeJSON(response, CommonResult.error(401, "Agent Token 无效"));
    }

}
