package io.github.qing936.xianyuops.module.system.controller.admin.auth;

import cn.hutool.core.collection.CollUtil;
import io.github.qing936.xianyuops.framework.common.util.object.BeanUtils;
import io.github.qing936.xianyuops.module.system.dal.dataobject.permission.MenuDO;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 登录权限菜单树辅助方法。 */
final class AuthMenuTreeHelper {

    private AuthMenuTreeHelper() {
    }

    /**
     * 补齐角色已分配菜单的祖先节点，仅用于构建返回给前端的菜单树。
     * 祖先节点不会写入角色菜单关联，避免同时授予其余子菜单。
     */
    static List<MenuDO> completeWithAncestors(Collection<MenuDO> assignedMenus, Collection<MenuDO> allMenus) {
        if (CollUtil.isEmpty(assignedMenus)) {
            return List.of();
        }
        Map<Long, MenuDO> allMenuMap = new LinkedHashMap<>();
        allMenus.forEach(menu -> allMenuMap.put(menu.getId(), menu));

        Map<Long, MenuDO> result = new LinkedHashMap<>();
        assignedMenus.forEach(menu -> result.put(menu.getId(), menu));
        for (MenuDO assignedMenu : assignedMenus) {
            MenuDO parent = allMenuMap.get(assignedMenu.getParentId());
            while (parent != null) {
                if (!result.containsKey(parent.getId())) {
                    MenuDO treeParent = BeanUtils.toBean(parent, MenuDO.class);
                    treeParent.setPermission(null);
                    result.put(parent.getId(), treeParent);
                }
                if (MenuDO.ID_ROOT.equals(parent.getParentId())) {
                    break;
                }
                parent = allMenuMap.get(parent.getParentId());
            }
        }
        return new ArrayList<>(result.values());
    }

}
