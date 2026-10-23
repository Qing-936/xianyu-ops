<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { Input, Tree, Spin } from 'ant-design-vue';
import { SearchOutlined } from '@ant-design/icons-vue';
import { getSimpleDeptList } from '#/api/system/dept';
import type { SystemDeptApi } from '#/api/system/dept';

defineOptions({ name: 'DeptTree' });

const emit = defineEmits<{ select: [deptId: number | undefined, deptName?: string] }>();

const loading = ref(false);
const deptList = ref<SystemDeptApi.Dept[]>([]);
const treeData = ref<any[]>([]);
const expandedKeys = ref<number[]>([]);
const selectedKeys = ref<number[]>([]);
const searchValue = ref('');

/** 将扁平部门列表构建成树 */
function buildTree(list: SystemDeptApi.Dept[]): any[] {
  const map = new Map<number, any>();
  const roots: any[] = [];
  for (const item of list) {
    map.set(item.id!, { ...item, children: [] });
  }
  for (const item of list) {
    const node = map.get(item.id!)!;
    if (item.parentId && map.has(item.parentId)) {
      map.get(item.parentId)!.children.push(node);
    } else {
      roots.push(node);
    }
  }
  return roots;
}

/** 递归收集节点 ID（用于展开） */
function collectIds(nodes: any[], ids: number[] = []): number[] {
  for (const node of nodes) {
    ids.push(node.id);
    if (node.children?.length) collectIds(node.children, ids);
  }
  return ids;
}

/** 搜索过滤：按名称匹配后重建树并展开 */
function handleSearch() {
  const value = searchValue.value.trim().toLowerCase();
  const filtered = value
    ? deptList.value.filter((item) => item.name.toLowerCase().includes(value))
    : deptList.value;
  treeData.value = buildTree(filtered);
  expandedKeys.value = collectIds(treeData.value);
}

/** 点击节点：再次点击已选中节点时取消筛选 */
function handleSelect(keys: number[], info: any) {
  selectedKeys.value = keys;
  if (keys.length > 0) {
    emit('select', keys[0], info?.node?.name);
  } else {
    emit('select', undefined);
  }
}

/** 外部重置时清空选中 */
function clearSelection() {
  selectedKeys.value = [];
}

defineExpose({ clearSelection });

onMounted(async () => {
  loading.value = true;
  try {
    deptList.value = (await getSimpleDeptList()) || [];
    treeData.value = buildTree(deptList.value);
    expandedKeys.value = collectIds(treeData.value);
  } finally {
    loading.value = false;
  }
});
</script>

<template>
  <div>
    <Input
      v-model:value="searchValue"
      placeholder="搜索部门"
      allow-clear
      @change="handleSearch"
    >
      <template #prefix><SearchOutlined /></template>
    </Input>
    <Spin :spinning="loading">
      <Tree
        v-if="treeData.length > 0"
        v-model:selectedKeys="selectedKeys"
        v-model:expandedKeys="expandedKeys"
        class="mt-2"
        :tree-data="treeData"
        :field-names="{ title: 'name', key: 'id', children: 'children' }"
        @select="(handleSelect as any)"
      />
      <div v-else-if="!loading" class="py-4 text-center" style="color: var(--color-text-muted, #8b8b9e)">
        暂无数据
      </div>
    </Spin>
  </div>
</template>
