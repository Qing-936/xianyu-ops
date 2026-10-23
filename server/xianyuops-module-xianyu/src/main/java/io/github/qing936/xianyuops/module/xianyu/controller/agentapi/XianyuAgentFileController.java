package io.github.qing936.xianyuops.module.xianyu.controller.agentapi;

import io.github.qing936.xianyuops.framework.common.pojo.CommonResult;
import io.github.qing936.xianyuops.framework.tenant.core.aop.TenantIgnore;
import io.github.qing936.xianyuops.framework.tenant.core.util.TenantUtils;
import io.github.qing936.xianyuops.module.xianyu.service.agent.XianyuAgentFileService;
import io.github.qing936.xianyuops.module.xianyu.service.agent.XianyuAgentProperties;
import io.github.qing936.xianyuops.module.xianyu.service.agent.XianyuAgentTokenService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.annotation.security.PermitAll;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.qing936.xianyuops.framework.common.pojo.CommonResult.success;

/**
 * 闲鱼机器 Agent 专用接口 - 验证截图上传（03 号文档 D-03-1）
 * <p>
 * Agent 不持 OSS 凭证，截图经本接口上传，Java 走 infra 文件服务落 OSS 后返回 URL，
 * Agent 将 URL 回传至 reply-task result 的 verifyImageUrl。
 * <p>
 * 本 Controller 位于 `controller.agentapi` 包（框架不识别、不加 /admin-api 前缀），
 * `@RequestMapping` 使用**绝对路径**（02 号文档 §4）。
 * <p>
 * 安全：Spring Security permitAll，鉴权由 {@code XianyuAgentTokenFilter} 承担；外部请求不带 tenant-id
 * 请求头，Controller 级别 {@code @TenantIgnore}，内部经 {@link TenantUtils} 在配置租户上下文中执行。
 */
@Tag(name = "闲鱼 Agent - 文件上传")
@RestController
@PermitAll
@TenantIgnore
@Validated
public class XianyuAgentFileController {

    @Resource
    private XianyuAgentFileService fileService;

    @Resource
    private XianyuAgentTokenService tokenService;

    @Resource
    private XianyuAgentProperties properties;

    @PostMapping("/agent-api/xianyu/file/upload")
    @Operation(summary = "上传验证截图（仅 image/*、≤10MB），返回 OSS URL")
    public CommonResult<String> upload(@RequestParam("file") MultipartFile file) {
        return success(inTenant(() -> fileService.upload(file, tokenService.resolveAgentId())));
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
