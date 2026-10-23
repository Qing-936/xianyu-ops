package io.github.qing936.xianyuops.framework.security.core.service;

import cn.hutool.core.collection.CollUtil;
import io.github.qing936.xianyuops.framework.common.biz.system.permission.PermissionCommonApi;
import io.github.qing936.xianyuops.framework.security.core.LoginUser;
import io.github.qing936.xianyuops.framework.security.core.util.SecurityFrameworkUtils;
import lombok.AllArgsConstructor;

import java.util.Arrays;

import static io.github.qing936.xianyuops.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId;
import static io.github.qing936.xianyuops.framework.security.core.util.SecurityFrameworkUtils.skipPermissionCheck;

/**
 * 默认的 {@link SecurityFrameworkService} 实现类
 *
 * @author XianyuOps系统
 */
@AllArgsConstructor
public class SecurityFrameworkServiceImpl implements SecurityFrameworkService {

    private final PermissionCommonApi permissionApi;

    /**
     * 功能权限校验总开关：false 时直接放行（临时调试用，见 {@link io.github.qing936.xianyuops.framework.security.config.SecurityProperties#getPermissionCheckEnable()}）
     */
    private final Boolean permissionCheckEnable;

    @Override
    public boolean hasPermission(String permission) {
        return hasAnyPermissions(permission);
    }

    @Override
    public boolean hasAnyPermissions(String... permissions) {
        // 调试开关：关闭功能权限校验时直接放行
        if (Boolean.FALSE.equals(permissionCheckEnable)) {
            return true;
        }
        // 特殊：跨租户访问
        if (skipPermissionCheck()) {
            return true;
        }

        // 权限校验
        Long userId = getLoginUserId();
        if (userId == null) {
            return false;
        }
        return permissionApi.hasAnyPermissions(userId, permissions);
    }

    @Override
    public boolean hasRole(String role) {
        return hasAnyRoles(role);
    }

    @Override
    public boolean hasAnyRoles(String... roles) {
        // 调试开关：关闭功能权限校验时直接放行
        if (Boolean.FALSE.equals(permissionCheckEnable)) {
            return true;
        }
        // 特殊：跨租户访问
        if (skipPermissionCheck()) {
            return true;
        }

        // 权限校验
        Long userId = getLoginUserId();
        if (userId == null) {
            return false;
        }
        return permissionApi.hasAnyRoles(userId, roles);
    }

    @Override
    public boolean hasScope(String scope) {
        return hasAnyScopes(scope);
    }

    @Override
    public boolean hasAnyScopes(String... scope) {
        // 调试开关：关闭功能权限校验时直接放行
        if (Boolean.FALSE.equals(permissionCheckEnable)) {
            return true;
        }
        // 特殊：跨租户访问
        if (skipPermissionCheck()) {
            return true;
        }

        // 权限校验
        LoginUser user = SecurityFrameworkUtils.getLoginUser();
        if (user == null) {
            return false;
        }
        return CollUtil.containsAny(user.getScopes(), Arrays.asList(scope));
    }

}
