package io.github.qing936.xianyuops.module.system.controller.admin.permission.vo.permission;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.Collections;
import java.util.Set;

@Schema(description = "管理后台 - 赋予角色菜单 Response VO")
@Data
public class PermissionAssignRoleMenuRespVO {

    @Schema(description = "被剔除的菜单编号（不在当前租户套餐内，未生效）", example = "1,3,5")
    private Set<Long> removedMenuIds = Collections.emptySet(); // 兜底

    public PermissionAssignRoleMenuRespVO setRemovedMenuIds(Set<Long> removedMenuIds) {
        this.removedMenuIds = removedMenuIds;
        return this;
    }

}
