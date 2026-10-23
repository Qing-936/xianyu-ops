package io.github.qing936.xianyuops.module.xianyu.controller.agentapi;

import io.github.qing936.xianyuops.framework.common.pojo.CommonResult;
import io.github.qing936.xianyuops.framework.tenant.core.aop.TenantIgnore;
import io.github.qing936.xianyuops.framework.tenant.core.util.TenantUtils;
import io.github.qing936.xianyuops.module.xianyu.controller.agentapi.vo.PollRespVO;
import io.github.qing936.xianyuops.module.xianyu.controller.agentapi.vo.ResultReqVO;
import io.github.qing936.xianyuops.module.xianyu.service.agent.XianyuAgentProperties;
import io.github.qing936.xianyuops.module.xianyu.service.agent.XianyuAgentTokenService;
import io.github.qing936.xianyuops.module.xianyu.service.replytask.XianyuReplyTaskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.annotation.security.PermitAll;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.qing936.xianyuops.framework.common.pojo.CommonResult.success;

/**
 * 闲鱼机器 Agent 专用接口 - 回复任务长轮询 / 结果回传
 * <p>
 * 本 Controller 位于 `controller.agentapi` 包（框架不识别、不加 /admin-api 前缀），
 * `@RequestMapping` 使用**绝对路径**（02 号文档 §4）。
 * <p>
 * 安全：Spring Security permitAll，鉴权由 {@code XianyuAgentTokenFilter} 承担；外部请求不带 tenant-id
 * 请求头，Controller 级别 {@code @TenantIgnore}，内部经 {@link TenantUtils} 在配置租户上下文中执行。
 * agentId 由 token 推导，推导调用点在租户上下文包裹之内（同 XianyuAgentApiController）。
 */
@Tag(name = "闲鱼 Agent - 回复任务")
@RestController
@PermitAll
@TenantIgnore
@Validated
public class XianyuReplyTaskApiController {

    @Resource
    private XianyuReplyTaskService replyTaskService;

    @Resource
    private XianyuAgentTokenService tokenService;

    @Resource
    private XianyuAgentProperties properties;

    @GetMapping("/agent-api/xianyu/reply-task/poll")
    @Operation(summary = "长轮询拉取回复任务（原子领取 pending → dispatched）")
    public CommonResult<PollRespVO> poll() {
        return success(inTenant(() -> replyTaskService.pollTasks(tokenService.resolveAgentId())));
    }

    @PostMapping("/agent-api/xianyu/reply-task/{taskId}/result")
    @Operation(summary = "回复任务结果回传（success/failed）")
    @Parameter(name = "taskId", description = "任务编号", required = true)
    public CommonResult<Boolean> result(@PathVariable("taskId") Long taskId,
                                        @Valid @RequestBody ResultReqVO reqVO) {
        inTenant(() -> {
            replyTaskService.reportResult(taskId, tokenService.resolveAgentId(), reqVO);
            return null;
        });
        return success(true);
    }

    /**
     * 在配置租户上下文中执行业务逻辑（使用 Runnable 版本，避免 Callable 版本包装业务异常导致错误码丢失，
     * 同 Agent 控制器 inTenant 样板）。
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
