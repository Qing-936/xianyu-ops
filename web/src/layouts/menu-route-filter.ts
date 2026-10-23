import type { RouteRecordRaw } from 'vue-router';

function resolveFullPath(parentPath: string, childPath: string): string {
  if (childPath.startsWith('/')) return childPath;
  return `${parentPath}/${childPath}`;
}

/** 按后端 RBAC 菜单路径递归过滤静态业务路由。 */
export function filterRoutesByMenuPaths(
  routes: RouteRecordRaw[],
  allowedPaths: ReadonlySet<string>,
  parentPath = '',
): RouteRecordRaw[] {
  return routes.flatMap((routeDef) => {
    const fullPath = resolveFullPath(parentPath, routeDef.path);
    if (!allowedPaths.has(fullPath)) return [];

    const children = routeDef.children
      ? filterRoutesByMenuPaths(routeDef.children, allowedPaths, fullPath)
      : undefined;
    if (routeDef.children?.length && children?.length === 0) return [];

    const filteredRoute = children === undefined
      ? { ...routeDef }
      : { ...routeDef, children };
    return [filteredRoute as RouteRecordRaw];
  });
}
