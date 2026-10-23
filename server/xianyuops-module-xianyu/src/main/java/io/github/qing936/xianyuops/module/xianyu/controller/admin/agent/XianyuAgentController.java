package io.github.qing936.xianyuops.module.xianyu.controller.admin.agent;

import io.github.qing936.xianyuops.framework.common.pojo.CommonResult;
import io.github.qing936.xianyuops.framework.security.core.LoginUser;
import io.github.qing936.xianyuops.framework.security.core.util.SecurityFrameworkUtils;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.agent.vo.AgentActorRespVO;
import io.github.qing936.xianyuops.module.system.api.agent.AgentActorApi;
import io.github.qing936.xianyuops.module.system.api.agent.dto.AgentActorRespDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;

import static io.github.qing936.xianyuops.framework.common.pojo.CommonResult.success;

/**
 * 闲鱼 Agent 支持接口（只读）。
 *
 * <p>仅提供 Agent 运行所需的服务端可信身份查询；不包含任何商品/订单写操作。
 * 身份只根据当前 Authorization 解析，不接受前端传入的 actor_id。
 */
@Tag(name = "管理后台 - 闲鱼 Agent")
@RestController
@RequestMapping("/app/xianyu/agent")
@Validated
public class XianyuAgentController {

    @Resource
    private AgentActorApi agentActorApi;

    @GetMapping("/actor")
    @Operation(summary = "获取当前登录用户的 Agent Actor 信息（服务端可信身份）")
    @PreAuthorize("@ss.hasPermission('app:xianyu-product:query')")
    public CommonResult<AgentActorRespVO> actor() {
        LoginUser loginUser = SecurityFrameworkUtils.getLoginUser();
        if (loginUser == null || loginUser.getId() == null) {
            return CommonResult.error(401, "未登录");
        }
        AgentActorRespDTO actor = agentActorApi.getActor(loginUser.getId());
        AgentActorRespVO vo = new AgentActorRespVO();
        vo.setUserId(loginUser.getId());
        vo.setTenantId(loginUser.getTenantId());
        if (actor != null) {
            vo.setUsername(actor.getUsername());
            vo.setNickname(actor.getNickname());
            vo.setRoles(actor.getRoles());
        } else {
            vo.setRoles(Collections.emptyList());
        }
        return success(vo);
    }

}
