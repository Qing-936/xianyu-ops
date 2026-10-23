package io.github.qing936.xianyuops.module.xianyu.controller.admin.agent;

import io.github.qing936.xianyuops.framework.common.pojo.CommonResult;
import io.github.qing936.xianyuops.framework.common.pojo.PageResult;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.agent.vo.XianyuAgentAccountRespVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.agent.vo.XianyuAgentEnrollCreateReqVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.agent.vo.XianyuAgentEnrollCreateRespVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.agent.vo.XianyuAgentEnrollPageReqVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.agent.vo.XianyuAgentEnrollRespVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.agent.vo.XianyuAgentEventPageReqVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.agent.vo.XianyuAgentEventRespVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.agent.vo.XianyuMachineAgentPageReqVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.agent.vo.XianyuMachineAgentRespVO;
import io.github.qing936.xianyuops.module.xianyu.service.agent.XianyuAgentEnrollService;
import io.github.qing936.xianyuops.module.xianyu.service.agent.XianyuAgentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static io.github.qing936.xianyuops.framework.common.pojo.CommonResult.success;

/**
 * 管理后台 - 闲鱼机器 Agent（02 号文档 §5.1）
 * <p>
 * 与同包既有 AI Agent actor 的 {@link XianyuAgentController} 共享 /app/xianyu/agent 前缀
 * （M1 评审 P2-4：管理端 URL 统一 /admin-api/app/xianyu/** 惯例），方法级路径无冲突。
 */
@Tag(name = "管理后台 - 闲鱼机器 Agent")
@RestController
@RequestMapping("/app/xianyu/agent")
@Validated
public class XianyuMachineAgentController {

    @Resource
    private XianyuAgentService agentService;

    @Resource
    private XianyuAgentEnrollService enrollService;

    @GetMapping("/page")
    @Operation(summary = "获取机器 Agent 分页")
    @PreAuthorize("@ss.hasPermission('app:xianyu-agent:query')")
    public CommonResult<PageResult<XianyuMachineAgentRespVO>> page(@Valid XianyuMachineAgentPageReqVO reqVO) {
        return success(agentService.getAgentPage(reqVO));
    }

    @GetMapping("/{id}/accounts")
    @Operation(summary = "获取某 Agent 的闲鱼号列表（含未对齐行）")
    @Parameter(name = "id", description = "机器 Agent 编号", required = true)
    @PreAuthorize("@ss.hasPermission('app:xianyu-agent:query')")
    public CommonResult<List<XianyuAgentAccountRespVO>> accounts(@PathVariable("id") Long id) {
        return success(agentService.getAgentAccounts(id));
    }

    @GetMapping("/{id}/events")
    @Operation(summary = "获取某 Agent 的事件流分页")
    @Parameter(name = "id", description = "机器 Agent 编号", required = true)
    @PreAuthorize("@ss.hasPermission('app:xianyu-agent:query')")
    public CommonResult<PageResult<XianyuAgentEventRespVO>> events(@PathVariable("id") Long id,
                                                                   @Valid XianyuAgentEventPageReqVO reqVO) {
        return success(agentService.getAgentEvents(id, reqVO));
    }

    @PutMapping("/{id}/token/reset")
    @Operation(summary = "重置专属 Token（返回新明文仅显示一次；旧 Token 立即失效，需同步更新对应机器的 .agent_token 后重启 Agent）")
    @Parameter(name = "id", description = "机器 Agent 编号", required = true)
    @PreAuthorize("@ss.hasPermission('app:xianyu-agent:update')")
    public CommonResult<String> resetToken(@PathVariable("id") Long id) {
        return success(agentService.resetToken(id));
    }

    // ========== 一次性注册码（新机器接入，替代原「引导 Token」全局长期密钥） ==========

    @PostMapping("/enroll/create")
    @Operation(summary = "生成一次性注册码（明文仅返回一次；新机器首次心跳用它注册，随后自动换发专属 Token）")
    @PreAuthorize("@ss.hasPermission('app:xianyu-agent:update')")
    public CommonResult<XianyuAgentEnrollCreateRespVO> createEnrollCode(
            @Valid @RequestBody XianyuAgentEnrollCreateReqVO reqVO) {
        return success(enrollService.createEnrollCode(reqVO));
    }

    @GetMapping("/enroll/page")
    @Operation(summary = "获取注册码分页（不含明文与哈希）")
    @PreAuthorize("@ss.hasPermission('app:xianyu-agent:query')")
    public CommonResult<PageResult<XianyuAgentEnrollRespVO>> enrollPage(@Valid XianyuAgentEnrollPageReqVO reqVO) {
        return success(enrollService.getEnrollPage(reqVO));
    }

    @DeleteMapping("/enroll/{id}")
    @Operation(summary = "撤销注册码（立即失效；不影响已签发的专属 Token）")
    @Parameter(name = "id", description = "注册码编号", required = true)
    @PreAuthorize("@ss.hasPermission('app:xianyu-agent:update')")
    public CommonResult<Boolean> revokeEnrollCode(@PathVariable("id") Long id) {
        enrollService.revokeEnrollCode(id);
        return success(true);
    }

}
