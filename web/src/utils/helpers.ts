/**
 * 通用工具函数
 */

/** 睡眠 */
export function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

/** 深拷贝 */
export function deepClone<T>(obj: T): T {
  return JSON.parse(JSON.stringify(obj));
}

/** 从路由 meta 中提取菜单数据 */
export interface MenuMeta {
  title: string;
  icon?: string;
  hideInMenu?: boolean;
  hideChildrenInMenu?: boolean;
  order?: number;
  activePath?: string;
  noCache?: boolean;
}

/** 树形结构转换 */
export function listToTree<T extends Record<string, any>>(
  list: T[],
  options: { idKey?: string; parentIdKey?: string; childrenKey?: string } = {},
): T[] {
  const { idKey = 'id', parentIdKey = 'parentId', childrenKey = 'children' } = options;
  const map = new Map<any, T>();
  const roots: T[] = [];

  for (const item of list) {
    map.set(item[idKey], { ...item, [childrenKey]: [] });
  }

  for (const item of list) {
    const node = map.get(item[idKey])!;
    const parentId = item[parentIdKey];
    if (parentId && map.has(parentId)) {
      map.get(parentId)![childrenKey].push(node);
    } else {
      roots.push(node);
    }
  }

  return roots;
}
