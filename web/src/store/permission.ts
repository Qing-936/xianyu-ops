import { defineStore } from 'pinia';

/** 后端菜单树节点（get-permission-info 返回的 menus） */
interface MenuNode {
  path?: string;
  children?: MenuNode[];
}

/** 递归扁平化菜单树为完整路径集合 */
function flattenMenuPaths(menus: MenuNode[], parentPath: string, out: string[]): void {
  for (const menu of menus || []) {
    if (!menu?.path) continue;
    // 子菜单 path 可能为相对路径（如 user）或绝对路径（如 /app/xianyu/service/message）
    const fullPath = menu.path.startsWith('/') ? menu.path : `${parentPath}/${menu.path}`;
    out.push(fullPath);
    if (Array.isArray(menu.children) && menu.children.length > 0) {
      flattenMenuPaths(menu.children, fullPath, out);
    }
  }
}

export const usePermissionStore = defineStore('permission', {
  state: (): {
    permissions: string[];
    /** 当前用户被分配的菜单完整路径集合 */
    userMenuPaths: string[];
    /** 是否启用菜单过滤（普通用户始终启用，超级管理员显式关闭） */
    menuFilterEnabled: boolean;
    /** 菜单权限是否已加载过 */
    menuLoaded: boolean;
    /** 本会话是否已尝试过加载（防止失败后反复请求，不持久化） */
    menuLoadAttempted: boolean;
  } => ({
    permissions: [],
    userMenuPaths: [],
    menuFilterEnabled: false,
    menuLoaded: false,
    menuLoadAttempted: false,
  }),

  getters: {
    /** 检查是否拥有指定权限码 */
    hasPermission: (state) => {
      return (permission: string) => state.permissions.includes(permission);
    },
    /** 用户菜单中第一个叶子路径（首个可访问页面），用于登录后/404 的默认跳转兜底 */
    firstLeafMenuPath: (state) => {
      return state.userMenuPaths.find((p) => !state.userMenuPaths.some((other) => other.startsWith(`${p}/`)));
    },
  },

  actions: {
    setPermissions(permissions: string[]) {
      this.permissions = permissions;
    },
    /** 设置用户菜单树，扁平化为路径集合 */
    setUserMenus(menus: MenuNode[], filterEnabled = true) {
      const paths: string[] = [];
      flattenMenuPaths(menus, '', paths);
      this.userMenuPaths = paths;
      this.menuFilterEnabled = filterEnabled;
      this.menuLoaded = true;
    },
    markMenuLoadAttempted() {
      this.menuLoadAttempted = true;
    },
    $reset() {
      this.permissions = [];
      this.userMenuPaths = [];
      this.menuFilterEnabled = false;
      this.menuLoaded = false;
      this.menuLoadAttempted = false;
    },
  },

  persist: {
    key: 'xianyuops-permission',
    pick: ['permissions', 'userMenuPaths', 'menuFilterEnabled', 'menuLoaded'],
  },
});
