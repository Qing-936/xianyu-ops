<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { Card, Table, Button, Space, Tag, Modal, message } from 'ant-design-vue';
import { PlusOutlined } from '@ant-design/icons-vue';
import { getMenuList, deleteMenu } from '#/api/system/menu';
import type { SystemMenuApi } from '#/api/system/menu';
import { formatDateTime } from '#/utils/format';
import MenuFormModal from './components/MenuFormModal.vue';

defineOptions({ name: 'SystemMenu' });

const loading = ref(false);
const menuList = ref<SystemMenuApi.Menu[]>([]);
const expandedKeys = ref<number[]>([]);

const columns = [
  { title: '菜单名称', dataIndex: 'name', width: 200 },
  { title: '图标', dataIndex: 'icon', width: 180 },
  { title: '排序', dataIndex: 'sort', width: 70 },
  { title: '权限标识', dataIndex: 'permission', width: 200 },
  { title: '组件路径', dataIndex: 'component', width: 180 },
  { title: '类型', dataIndex: 'type', width: 80 },
  { title: '状态', dataIndex: 'status', width: 80 },
  { title: '创建时间', dataIndex: 'createTime', width: 170, customRender: ({ text }: { text: string }) => formatDateTime(text) },
  { title: '操作', key: 'operation', width: 220, fixed: 'right' as const },
];

/** 菜单类型标签 */
const typeTagMap: Record<number, { text: string; color: string }> = {
  1: { text: '目录', color: 'blue' },
  2: { text: '菜单', color: 'green' },
  3: { text: '按钮', color: 'orange' },
};

/** 将扁平菜单列表转为树形结构 */
function buildMenuTree(list: SystemMenuApi.Menu[]): SystemMenuApi.Menu[] {
  const map = new Map<number, SystemMenuApi.Menu>();
  const roots: SystemMenuApi.Menu[] = [];
  for (const m of list) {
    map.set(m.id, { ...m, children: [] });
  }
  for (const m of list) {
    const node = map.get(m.id)!;
    if (m.parentId && map.has(m.parentId)) {
      map.get(m.parentId)!.children!.push(node);
    } else {
      roots.push(node);
    }
  }
  return roots;
}

const dataSource = computed(() => buildMenuTree(menuList.value));

/** 递归收集所有节点 ID（用于展开全部） */
function collectIds(nodes: SystemMenuApi.Menu[], ids: number[] = []): number[] {
  for (const node of nodes) {
    ids.push(node.id);
    if (node.children?.length) collectIds(node.children, ids);
  }
  return ids;
}

async function loadData() {
  loading.value = true;
  try {
    menuList.value = (await getMenuList()) || [];
    // 默认展开全部
    expandedKeys.value = collectIds(dataSource.value);
  } finally {
    loading.value = false;
  }
}

function handleDelete(id: number) {
  // 二次确认用 Modal.confirm：操作列 fixed 列中的 Popconfirm 浮层定位/动画有 bug（打开后会反复重放缩放动画）
  Modal.confirm({ title: '确认删除', content: '确定要删除该菜单吗？', async onOk() { await deleteMenu(id); message.success('删除成功'); loadData(); } });
}

onMounted(loadData);

// ============ 新增/编辑弹窗 ============
const formModalRef = ref<InstanceType<typeof MenuFormModal>>();

function openModal(record?: SystemMenuApi.Menu, parentId?: number) {
  formModalRef.value?.open(record, parentId);
}
</script>

<template>
  <Card title="菜单管理">
    <div class="mb-4">
      <Space>
        <Button type="primary" @click="openModal()"><PlusOutlined /> 新增菜单</Button>
        <Button @click="expandedKeys = collectIds(dataSource)">展开全部</Button>
        <Button @click="expandedKeys = []">折叠全部</Button>
      </Space>
    </div>

    <Table
      :columns="columns"
      :data-source="dataSource"
      :loading="loading"
      :pagination="false"
      :expanded-row-keys="expandedKeys"
      row-key="id"
      :scroll="{ x: 1300 }"
      @expanded-rows-change="(keys: any[]) => (expandedKeys = keys as number[])"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.dataIndex === 'type'">
          <Tag :color="typeTagMap[record.type]?.color">{{ typeTagMap[record.type]?.text || record.type }}</Tag>
        </template>
        <template v-if="column.dataIndex === 'status'">
          <Tag :color="record.status === 0 ? 'green' : 'red'">{{ record.status === 0 ? '正常' : '停用' }}</Tag>
        </template>
        <template v-if="column.key === 'operation'">
          <Space>
            <Button type="link" size="small" @click="openModal(undefined, record.id)">新增下级</Button>
            <Button type="link" size="small" @click="openModal(record as SystemMenuApi.Menu)">编辑</Button>
            <Button type="link" size="small" danger @click="handleDelete(record.id)">删除</Button>
          </Space>
        </template>
      </template>
    </Table>

    <!-- 新增/编辑弹窗 -->
    <MenuFormModal ref="formModalRef" @success="loadData" />
  </Card>
</template>
