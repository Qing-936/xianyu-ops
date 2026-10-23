package io.github.qing936.xianyuops.module.xianyu.controller.agentapi;

import io.github.qing936.xianyuops.framework.common.pojo.CommonResult;
import io.github.qing936.xianyuops.framework.tenant.core.aop.TenantIgnore;
import io.github.qing936.xianyuops.framework.tenant.core.util.TenantUtils;
import io.github.qing936.xianyuops.module.xianyu.controller.agentapi.vo.UploadReqVO;
import io.github.qing936.xianyuops.module.xianyu.controller.agentapi.vo.UploadRespVO;
import io.github.qing936.xianyuops.module.xianyu.service.agent.XianyuAgentProperties;
import io.github.qing936.xianyuops.module.xianyu.service.agent.XianyuAgentTokenService;
import io.github.qing936.xianyuops.module.xianyu.service.message.XianyuMessageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.annotation.security.PermitAll;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.qing936.xianyuops.framework.common.pojo.CommonResult.success;

/**
 * 闲鱼机器 Agent 专用接口 - 买家消息批量上报
 * <p>
 * 本 Controller 位于 `controller.agentapi` 包（框架不识别、不加 /admin-api 前缀），
 * `@RequestMapping` 使用**绝对路径**（02 号文档 §4）。
 * <p>
 * 安全：Spring Security permitAll，鉴权由 {@code XianyuAgentTokenFilter} 承担；外部请求不带 tenant-id
 * 请求头，Controller 级别 {@code @TenantIgnore}，内部经 {@link TenantUtils} 在配置租户上下文中执行。
 * agentId 由 token 推导，推导调用点在租户上下文包裹之内（同 XianyuAgentApiController）。
 */
@Tag(name = "闲鱼 Agent - 消息上报")
@RestController
@PermitAll
@TenantIgnore
@Validated
public class XianyuMessageApiController {

    @Resource
    private XianyuMessageService messageService;

    @Resource
    private XianyuAgentTokenService tokenService;

    @Resource
    private XianyuAgentProperties properties;

    @PostMapping("/agent-api/xianyu/message/upload")
    @Operation(summary = "批量上报买家消息（逐条判重 + 账号对齐）")
    public CommonResult<UploadRespVO> upload(@Valid @RequestBody UploadReqVO reqVO) {
        return success(inTenant(() -> messageService.upload(tokenService.resolveAgentId(), reqVO.getMessages())));
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
