package io.github.qing936.xianyuops.module.system.api.agent;

import io.github.qing936.xianyuops.framework.common.enums.CommonStatusEnum;
import io.github.qing936.xianyuops.module.system.api.agent.dto.AgentActorRespDTO;
import io.github.qing936.xianyuops.module.system.dal.dataobject.permission.RoleDO;
import io.github.qing936.xianyuops.module.system.dal.dataobject.user.AdminUserDO;
import io.github.qing936.xianyuops.module.system.service.permission.PermissionService;
import io.github.qing936.xianyuops.module.system.service.permission.RoleService;
import io.github.qing936.xianyuops.module.system.service.user.AdminUserService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * {@link AgentActorApi} 实现：查询登录用户的用户名 / 昵称 / 角色标识。
 */
@Service
public class AgentActorApiImpl implements AgentActorApi {

    @Resource
    private AdminUserService userService;

    @Resource
    private PermissionService permissionService;

    @Resource
    private RoleService roleService;

    @Override
    public AgentActorRespDTO getActor(Long userId) {
        AdminUserDO user = userService.getUser(userId);
        if (user == null) {
            return null;
        }
        // 角色：排除禁用角色（与 AuthController.getPermissionInfo 一致）
        Set<Long> roleIds = permissionService.getUserRoleIdListByUserId(userId);
        List<RoleDO> roles = roleIds.isEmpty() ? Collections.emptyList() : roleService.getRoleList(roleIds);
        roles.removeIf(role -> !CommonStatusEnum.ENABLE.getStatus().equals(role.getStatus()));

        AgentActorRespDTO dto = new AgentActorRespDTO();
        dto.setUserId(userId);
        dto.setUsername(user.getUsername());
        dto.setNickname(user.getNickname());
        dto.setRoles(roles.stream().map(RoleDO::getCode).toList());
        return dto;
    }

}
