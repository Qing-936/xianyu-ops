package io.github.qing936.xianyuops.module.system.api.permission;

import io.github.qing936.xianyuops.framework.common.biz.system.permission.PermissionCommonApi;

import java.util.Collection;
import java.util.Set;

/**
 * 权限 API 接口
 *
 * @author XianyuOps系统
 */
public interface PermissionApi extends PermissionCommonApi {

    /**
     * 获得拥有多个角色的用户编号集合
     *
     * @param roleIds 角色编号集合
     * @return 用户编号集合
     */
    Set<Long> getUserRoleIdListByRoleIds(Collection<Long> roleIds);

}
