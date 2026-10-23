/**
 * 树结构工具：将带 parentId 的扁平列表转为树
 * 提取自 views/system/role/index.vue 的内联实现，泛型化供多页面复用
 */

export interface Treeable {
  id: number;
  parentId: number;
  children?: Treeable[];
}

/**
 * 扁平列表转树（父节点不存在时作为根节点）
 * @param list 扁平列表（元素需含 id / parentId）
 * @returns 树形结构（节点为原对象的浅拷贝，children 已填充）
 */
export function handleTree<T extends Treeable>(list: T[]): T[] {
  const map = new Map<number, T & { children: T[] }>();
  const roots: T[] = [];
  for (const item of list) {
    map.set(item.id, { ...item, children: [] });
  }
  for (const item of list) {
    const node = map.get(item.id)!;
    if (item.parentId && map.has(item.parentId)) {
      (map.get(item.parentId)!.children as T[]).push(node);
    } else {
      roots.push(node);
    }
  }
  return roots;
}

/** 递归收集树中所有节点的 id */
export function collectTreeIds<T extends Treeable>(tree: T[]): number[] {
  const ids: number[] = [];
  const walk = (nodes: T[]) => {
    for (const node of nodes) {
      ids.push(node.id);
      if (node.children?.length) {
        walk(node.children as T[]);
      }
    }
  };
  walk(tree);
  return ids;
}
