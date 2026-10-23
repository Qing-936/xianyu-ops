package io.github.qing936.xianyuops.module.system.controller.admin.permission;

import cn.hutool.core.collection.CollUtil;
import io.github.qing936.xianyuops.framework.common.pojo.CommonResult;
import io.github.qing936.xianyuops.module.system.controller.admin.permission.vo.permission.PermissionAssignRoleDataScopeReqVO;
import io.github.qing936.xianyuops.module.system.controller.admin.permission.vo.permission.PermissionAssignRoleMenuReqVO;
import io.github.qing936.xianyuops.module.system.controller.admin.permission.vo.permission.PermissionAssignRoleMenuRespVO;
import io.github.qing936.xianyuops.module.system.controller.admin.permission.vo.permission.PermissionAssignUserRoleReqVO;
import io.github.qing936.xianyuops.module.system.service.permission.PermissionService;
import io.github.qing936.xianyuops.module.system.service.tenant.TenantService;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import java.util.LinkedHashSet;
import java.util.Set;

import static io.github.qing936.xianyuops.framework.common.pojo.CommonResult.success;

/**
 * 权限 Controller，提供赋予用户、角色的权限的 API 接口
 *
 * @author XianyuOps系统
 */
@Tag(name = "管理后台 - 权限")
@RestController
@RequestMapping("/system/permission")
public class PermissionController {

    @Resource
    private PermissionService permissionService;
    @Resource
    private TenantService tenantService;

    @Operation(summary = "获得角色拥有的菜单编号")
    @Parameter(name = "roleId", description = "角色编号", required = true)
    @GetMapping("/list-role-menus")
    @PreAuthorize("@ss.hasPermission('system:permission:assign-role-menu')")
    public CommonResult<Set<Long>> getRoleMenuList(Long roleId) {
        return success(permissionService.getRoleMenuListByRoleId(roleId));
    }

    @PostMapping("/assign-role-menu")
    @Operation(summary = "赋予角色菜单")
    @PreAuthorize("@ss.hasPermission('system:permission:assign-role-menu')")
    public CommonResult<PermissionAssignRoleMenuRespVO> assignRoleMenu(@Validated @RequestBody PermissionAssignRoleMenuReqVO reqVO) {
        // 开启多租户的情况下，需要过滤掉未开通的菜单。
        // 注意：必须记录被剔除的菜单编号并返回给前端，否则前端会误以为“全部保存成功”
        Set<Long> removedMenuIds = new LinkedHashSet<>();
        tenantService.handleTenantMenu(menuIds -> reqVO.getMenuIds().removeIf(menuId -> {
            boolean removed = !CollUtil.contains(menuIds, menuId);
            if (removed) {
                removedMenuIds.add(menuId);
            }
            return removed;
        }));

        // 执行菜单的分配
        permissionService.assignRoleMenu(reqVO.getRoleId(), reqVO.getMenuIds());
        return success(new PermissionAssignRoleMenuRespVO().setRemovedMenuIds(removedMenuIds));
    }

    @PostMapping("/assign-role-data-scope")
    @Operation(summary = "赋予角色数据权限")
    @PreAuthorize("@ss.hasPermission('system:permission:assign-role-data-scope')")
    public CommonResult<Boolean> assignRoleDataScope(@Valid @RequestBody PermissionAssignRoleDataScopeReqVO reqVO) {
        permissionService.assignRoleDataScope(reqVO.getRoleId(), reqVO.getDataScope(), reqVO.getDataScopeDeptIds());
        return success(true);
    }

    @Operation(summary = "获得管理员拥有的角色编号列表")
    @Parameter(name = "userId", description = "用户编号", required = true)
    @GetMapping("/list-user-roles")
    @PreAuthorize("@ss.hasPermission('system:permission:assign-user-role')")
    public CommonResult<Set<Long>> listAdminRoles(@RequestParam("userId") Long userId) {
        return success(permissionService.getUserRoleIdListByUserId(userId));
    }

    @Operation(summary = "赋予用户角色")
    @PostMapping("/assign-user-role")
    @PreAuthorize("@ss.hasPermission('system:permission:assign-user-role')")
    public CommonResult<Boolean> assignUserRole(@Validated @RequestBody PermissionAssignUserRoleReqVO reqVO) {
        permissionService.assignUserRole(reqVO.getUserId(), reqVO.getRoleIds());
        return success(true);
    }

}
