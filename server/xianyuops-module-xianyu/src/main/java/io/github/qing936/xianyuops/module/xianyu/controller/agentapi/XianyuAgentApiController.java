package io.github.qing936.xianyuops.module.xianyu.controller.agentapi;

import io.github.qing936.xianyuops.framework.common.pojo.CommonResult;
import io.github.qing936.xianyuops.framework.tenant.core.aop.TenantIgnore;
import io.github.qing936.xianyuops.framework.tenant.core.util.TenantUtils;
import io.github.qing936.xianyuops.module.xianyu.controller.agentapi.vo.EventReqVO;
import io.github.qing936.xianyuops.module.xianyu.controller.agentapi.vo.HeartbeatReqVO;
import io.github.qing936.xianyuops.module.xianyu.controller.agentapi.vo.HeartbeatRespVO;
import io.github.qing936.xianyuops.module.xianyu.service.agent.AgentTokenMismatchException;
import io.github.qing936.xianyuops.module.xianyu.service.agent.XianyuAgentEventService;
import io.github.qing936.xianyuops.module.xianyu.service.agent.XianyuAgentProperties;
import io.github.qing936.xianyuops.module.xianyu.service.agent.XianyuAgentService;
import io.github.qing936.xianyuops.module.xianyu.service.agent.XianyuAgentTokenService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.annotation.security.PermitAll;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.qing936.xianyuops.framework.common.pojo.CommonResult.success;

/**
 * 闲鱼机器 Agent 专用接口 - 心跳 / 运行事件
 * <p>
 * 注意：本 Controller 位于 `controller.agentapi` 包（框架不识别、不加 /admin-api 前缀），
 * `@RequestMapping` 使用**绝对路径**（02 号文档 §4，评审 P0-1）。
 * <p>
 * 安全：Spring Security permitAll（SecurityConfiguration `/agent-api/**`），鉴权由
 * {@code XianyuAgentTokenFilter}（X-Agent-Token）承担；外部请求不带 tenant-id 请求头，
 * Controller 级别 {@code @TenantIgnore}，内部经 {@link TenantUtils} 在配置租户上下文中执行。
 * <p>
 * agentId 推导（02 号文档 §4 开头）：
 * - heartbeat 不经过 {@link XianyuAgentTokenService#resolveAgentId()}（注册入口，Agent 尚不存在时推导会抛异常死锁）；
 * - event 经 token 推导，且推导调用点必须在租户上下文包裹之内。
 */
@Tag(name = "闲鱼 Agent - 心跳与事件")
@RestController
@PermitAll
@TenantIgnore
@Validated
@Slf4j
public class XianyuAgentApiController {

    @Resource
    private XianyuAgentService agentService;

    @Resource
    private XianyuAgentEventService eventService;

    @Resource
    private XianyuAgentTokenService tokenService;

    @Resource
    private XianyuAgentProperties properties;

    @PostMapping("/agent-api/xianyu/agent/heartbeat")
    @Operation(summary = "心跳（注册 + 状态更新 + 账号对齐）")
    public CommonResult<HeartbeatRespVO> heartbeat(@Valid @RequestBody HeartbeatReqVO reqVO) {
        // 注册入口：不经过 resolveAgentId，按 machineCode 查找/注册；身份验证在 Service（M5 一机一 Token）
        try {
            return success(inTenant(() -> agentService.heartbeat(reqVO)));
        } catch (AgentTokenMismatchException e) {
            // 401 语义与 Filter 对齐：Agent 端按 03 决策 D-03-6 记日志后退出进程（人工处置后重启）
            log.warn("[xianyu-agent] 心跳身份校验失败: {}", e.getMessage());
            return CommonResult.error(401, e.getMessage());
        }
    }

    @PostMapping("/agent-api/xianyu/event")
    @Operation(summary = "运行事件上报")
    public CommonResult<Boolean> event(@Valid @RequestBody EventReqVO reqVO) {
        inTenant(() -> {
            eventService.report(tokenService.resolveAgentId(), reqVO);
            return null;
        });
        return success(true);
    }

    /**
     * 在配置租户上下文中执行业务逻辑（使用 Runnable 版本，避免 Callable 版本包装业务异常导致错误码丢失，
     * 同 Agent 控制器 inTenant 样板；TenantUtils 的 Callable 重载会把 RuntimeException 再包一层）。
     */
    private <T> T inTenant(Callable<T> callable) {
        AtomicReference<T> result = new AtomicReference<>();
        TenantUtils.execute(properties.getTenantId(), () -> {
            try {
                result.set(callable.call());
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        return result.get();
    }

}
