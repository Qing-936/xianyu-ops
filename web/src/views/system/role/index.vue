<script setup lang="ts">
import { ref, onMounted, computed } from 'vue';
import {
  Card, Table, Button, Space, Tag, Modal, message, Form, Input,
  Select, InputNumber, RadioGroup, Radio, Switch, Tree, Row, Col,
} from 'ant-design-vue';
import { PlusOutlined, SearchOutlined, ReloadOutlined } from '@ant-design/icons-vue';
import { getRolePage, createRole, updateRole, deleteRole, getRole } from '#/api/system/role';
import { getRoleMenuList, assignRoleMenu, assignRoleDataScope } from '#/api/system/permission';
import { getSimpleMenusList } from '#/api/system/menu';
import { getSimpleDeptList } from '#/api/system/dept';
import type { SystemRoleApi } from '#/api/system/role';
import type { SystemMenuApi } from '#/api/system/menu';
import type { SystemDeptApi } from '#/api/system/dept';
import { formatDateTime } from '#/utils/format';

defineOptions({ name: 'SystemRole' });

// ============ 列表相关 ============
const loading = ref(false);
const dataSource = ref<SystemRoleApi.Role[]>([]);
const total = ref(0);
const pagination = ref({ pageNo: 1, pageSize: 20 });
const searchForm = ref({ name: '', code: '', status: undefined as number | undefined });

const columns = [
  { title: '角色编号', dataIndex: 'id', width: 80 },
  { title: '角色名称', dataIndex: 'name', width: 150 },
  { title: '角色标识', dataIndex: 'code', width: 150 },
  { title: '排序', dataIndex: 'sort', width: 80 },
  { title: '状态', dataIndex: 'status', width: 100 },
  { title: '创建时间', dataIndex: 'createTime', width: 180, customRender: ({ text }: { text: string }) => formatDateTime(text) },
  { title: '操作', key: 'operation', width: 360, fixed: 'right' as const },
];

async function loadData() {
  loading.value = true;
  try {
    const res = await getRolePage({ ...pagination.value, ...searchForm.value });
    dataSource.value = res.list || [];
    total.value = res.total || 0;
  } finally {
    loading.value = false;
  }
}

function handleSearch() { pagination.value.pageNo = 1; loadData(); }
function handleReset() { searchForm.value = { name: '', code: '', status: undefined }; handleSearch(); }

function handleDelete(id: number) {
  Modal.confirm({ title: '确认删除', content: '确定要删除该角色吗？', async onOk() { await deleteRole(id); message.success('删除成功'); loadData(); } });
}

onMounted(loadData);

// ============ 新增/编辑 Modal ============
const modalVisible = ref(false);
const modalLoading = ref(false);
const modalTitle = computed(() => (formData.value?.id ? '编辑角色' : '新增角色'));
const formData = ref<Partial<SystemRoleApi.Role>>({});
const FormItem = Form.Item;

async function openModal(record?: SystemRoleApi.Role) {
  if (record?.id) {
    const detail = await getRole(record.id!);
    formData.value = { ...detail };
  } else {
    formData.value = { status: 0, sort: 0 };
  }
  modalVisible.value = true;
}

async function handleModalOk() {
  modalLoading.value = true;
  try {
    if (formData.value?.id) {
      await updateRole(formData.value as SystemRoleApi.Role);
    } else {
      await createRole(formData.value as SystemRoleApi.Role);
    }
    message.success('操作成功');
    modalVisible.value = false;
    loadData();
  } finally {
    modalLoading.value = false;
  }
}

// ============ 分配菜单权限 Modal ============
const menuModalVisible = ref(false);
const menuModalLoading = ref(false);
const menuRoleId = ref<number>();
const menuList = ref<SystemMenuApi.Menu[]>([]);
const checkedMenuKeys = ref<number[]>([]);
const expandedMenuKeys = ref<number[]>([]);
const autoExpandParent = ref(true);
/** 角色当前在库中真实绑定的菜单（用于保存前后做差异提示与回读校验） */
const storedMenuIds = ref<number[]>([]);
/** 已授权但不在可选菜单中的菜单（被停用/删除或不在当前租户套餐内），保存后会被解除授权 */
const missingMenuIds = ref<number[]>([]);

/** 将菜单列表转为 Tree 结构 */
function buildMenuTree(menus: SystemMenuApi.Menu[]): SystemMenuApi.Menu[] {
  const map = new Map<number, SystemMenuApi.Menu>();
  const roots: SystemMenuApi.Menu[] = [];
  for (const m of menus) { map.set(m.id, { ...m, children: [] }); }
  for (const m of menus) {
    const node = map.get(m.id)!;
    if (m.parentId && map.has(m.parentId)) {
      map.get(m.parentId)!.children!.push(node);
    } else {
      roots.push(node);
    }
  }
  return roots;
}

const menuTreeData = computed(() => buildMenuTree(menuList.value));

const menuTreeFieldNames = { title: 'name', key: 'id', children: 'children' };

/** 菜单父子关系索引 */
const menuRelation = computed(() => {
  const parentMap = new Map<number, number>();
  const childrenMap = new Map<number, number[]>();
  for (const m of menuList.value) {
    parentMap.set(m.id, m.parentId ?? 0);
    if (m.parentId) {
      const siblings = childrenMap.get(m.parentId);
      if (siblings) {
        siblings.push(m.id);
      } else {
        childrenMap.set(m.parentId, [m.id]);
      }
    }
  }
  return { parentMap, childrenMap };
});

const menuNameMap = computed(() => new Map(menuList.value.map((m) => [m.id, m.name])));

function formatMenuNames(ids: number[]): string {
  const names = ids.map((id) => menuNameMap.value.get(id) || `#${id}`);
  const preview = names.slice(0, 6).join('、');
  return names.length > 6 ? `${preview} 等 ${names.length} 个` : preview;
}

/** 向下闭包：把勾选的节点扩展为其全部后代 —— “勾父 = 授整棵子树” */
function expandToDescendants(ids: number[]): number[] {
  const { childrenMap } = menuRelation.value;
  const result = new Set(ids);
  const stack = [...ids];
  while (stack.length > 0) {
    const currentId = stack.pop()!;
    for (const childId of childrenMap.get(currentId) || []) {
      if (!result.has(childId)) {
        result.add(childId);
        stack.push(childId);
      }
    }
  }
  return [...result];
}

/**
 * 补齐祖先目录。后端登录构树时会自动补齐祖先，此处保持一致，
 * 使保存结果稳定，避免每次保存都把父级目录当作“解除授权”处理。
 */
function withAncestors(ids: number[]): number[] {
  const { parentMap } = menuRelation.value;
  const result = new Set(ids);
  for (const id of ids) {
    let parentId = parentMap.get(id) ?? 0;
    while (parentId && parentMap.has(parentId)) {
      if (result.has(parentId)) {
        break;
      }
      result.add(parentId);
      parentId = parentMap.get(parentId) ?? 0;
    }
  }
  return [...result];
}

/**
 * 极大化：仅保留“没有其他被勾选后代”的节点，再交给 Tree 级联渲染。
 * 避免历史遗留的“只绑目录”数据（如仅绑了 应用管理/闲鱼/货源管理）被级联展开成整棵子树。
 */
function minimizeToDeepest(ids: number[]): number[] {
  const { childrenMap } = menuRelation.value;
  const selected = new Set(ids);
  const result: number[] = [];
  for (const id of selected) {
    let hasSelectedDescendant = false;
    const stack = [...(childrenMap.get(id) || [])];
    while (stack.length > 0) {
      const childId = stack.pop()!;
      if (selected.has(childId)) {
        hasSelectedDescendant = true;
        break;
      }
      stack.push(...(childrenMap.get(childId) || []));
    }
    if (!hasSelectedDescendant) {
      result.push(id);
    }
  }
  return result;
}

async function openMenuModal(record: SystemRoleApi.Role) {
  menuRoleId.value = record.id;
  // 加载菜单列表 + 角色已有的菜单
  const [menus, roleMenuIds] = await Promise.all([getSimpleMenusList(), getRoleMenuList(record.id!)]);
  menuList.value = menus || [];
  storedMenuIds.value = roleMenuIds || [];
  const selectableIds = new Set(menuList.value.map((m) => m.id));
  // 已授权但不在可选菜单中：保存后会被解除授权，必须显式提示
  missingMenuIds.value = storedMenuIds.value.filter((id) => !selectableIds.has(id));
  // 回显：只喂“最深层”节点，由 Tree 级联渲染出父级勾选态，确保所见即所存
  checkedMenuKeys.value = minimizeToDeepest(storedMenuIds.value.filter((id) => selectableIds.has(id)));
  expandedMenuKeys.value = menuList.value.filter((m) => m.parentId === 0).map((m) => m.id);
  autoExpandParent.value = true;
  menuModalVisible.value = true;
  if (missingMenuIds.value.length > 0) {
    message.warning(`有 ${missingMenuIds.value.length} 个已授权菜单已停用/删除或不在当前租户套餐内，保存后将解除授权`);
  }
}

function onMenuCheck(checkedKeys: any) {
  checkedMenuKeys.value = checkedKeys.checked || checkedKeys;
}

/** 保存前差异确认，避免“静默授予 / 静默解除” */
function confirmMenuDiff(submitMenuIds: number[]): Promise<boolean> {
  const storedSet = new Set(storedMenuIds.value);
  const selectableSet = new Set(menuList.value.map((m) => m.id));
  const submitSet = new Set(submitMenuIds);
  const newlyGranted = submitMenuIds.filter((id) => !storedSet.has(id));
  const revoked = [
    ...missingMenuIds.value,
    ...storedMenuIds.value.filter((id) => selectableSet.has(id) && !submitSet.has(id)),
  ];
  if (newlyGranted.length === 0 && revoked.length === 0) {
    return Promise.resolve(true);
  }
  const lines: string[] = [];
  if (newlyGranted.length > 0) {
    lines.push(`新增授权 ${newlyGranted.length} 个：${formatMenuNames(newlyGranted)}`);
  }
  if (revoked.length > 0) {
    lines.push(`解除授权 ${revoked.length} 个：${formatMenuNames(revoked)}`);
  }
  return new Promise<boolean>((resolve) => {
    Modal.confirm({
      title: '确认菜单授权变更',
      content: lines.join('；'),
      okText: '确认保存',
      cancelText: '取消',
      onOk: () => resolve(true),
      onCancel: () => resolve(false),
    });
  });
}

async function handleMenuModalOk() {
  // 勾父 = 授整棵子树：提交前补齐被勾选节点的全部后代，并补齐祖先目录
  const submitMenuIds = withAncestors(expandToDescendants(checkedMenuKeys.value));
  const confirmed = await confirmMenuDiff(submitMenuIds);
  if (!confirmed) {
    return;
  }
  menuModalLoading.value = true;
  try {
    const res = await assignRoleMenu({ roleId: menuRoleId.value!, menuIds: submitMenuIds });
    // 后端会剔除“当前租户套餐未开通”的菜单，需明确提示，而不是笼统的“分配成功”
    const removedMenuIds = res?.removedMenuIds || [];
    // 保存后回读，让界面反映真实落库结果
    const latest = await getRoleMenuList(menuRoleId.value!);
    storedMenuIds.value = latest || [];
    const selectableSet = new Set(menuList.value.map((m) => m.id));
    missingMenuIds.value = storedMenuIds.value.filter((id) => !selectableSet.has(id));
    checkedMenuKeys.value = minimizeToDeepest(storedMenuIds.value.filter((id) => selectableSet.has(id)));
    if (removedMenuIds.length > 0) {
      message.warning(`部分菜单不在当前租户套餐内，未生效：${formatMenuNames(removedMenuIds)}`);
    } else {
      message.success('分配成功');
      menuModalVisible.value = false;
    }
  } finally {
    menuModalLoading.value = false;
  }
}

function onMenuExpand(keys: number[]) {
  expandedMenuKeys.value = keys;
  autoExpandParent.value = false;
}

// ============ 数据权限 Modal ============
const dataScopeModalVisible = ref(false);
const dataScopeModalLoading = ref(false);
const dataScopeRoleId = ref<number>();
const dataScopeForm = ref<{ dataScope: number; dataScopeDeptIds: number[] }>({ dataScope: 1, dataScopeDeptIds: [] });
const deptList = ref<SystemDeptApi.Dept[]>([]);

/** 数据范围选项（对应后端 DataScopeEnum） */
const dataScopeOptions = [
  { label: '全部数据权限', value: 1 },
  { label: '指定部门数据权限', value: 2 },
  { label: '本部门数据权限', value: 3 },
  { label: '本部门及以下数据权限', value: 4 },
  { label: '仅本人数据权限', value: 5 },
];

/** 将扁平部门列表转为 Tree 结构 */
function buildDeptTree(list: SystemDeptApi.Dept[]): any[] {
  const map = new Map<number, any>();
  const roots: any[] = [];
  for (const d of list) { map.set(d.id!, { ...d, children: [] }); }
  for (const d of list) {
    const node = map.get(d.id!)!;
    if (d.parentId && map.has(d.parentId)) {
      map.get(d.parentId)!.children.push(node);
    } else {
      roots.push(node);
    }
  }
  return roots;
}

const deptTreeData = computed(() => buildDeptTree(deptList.value));
const deptTreeFieldNames = { title: 'name', key: 'id', children: 'children' };

async function openDataScopeModal(record: SystemRoleApi.Role) {
  dataScopeRoleId.value = record.id;
  // 并行加载角色详情（回填数据范围）+ 部门列表
  const [detail, depts] = await Promise.all([getRole(record.id!), getSimpleDeptList()]);
  deptList.value = depts || [];
  dataScopeForm.value = {
    dataScope: detail.dataScope ?? 1,
    dataScopeDeptIds: detail.dataScopeDeptIds || [],
  };
  dataScopeModalVisible.value = true;
}

function onDeptCheck(checkedKeys: any) {
  dataScopeForm.value.dataScopeDeptIds = checkedKeys.checked || checkedKeys;
}

async function handleDataScopeModalOk() {
  if (dataScopeForm.value.dataScope === 2 && dataScopeForm.value.dataScopeDeptIds.length === 0) {
    message.warning('请选择指定部门');
    return;
  }
  dataScopeModalLoading.value = true;
  try {
    await assignRoleDataScope({
      roleId: dataScopeRoleId.value!,
      dataScope: dataScopeForm.value.dataScope,
      dataScopeDeptIds: dataScopeForm.value.dataScope === 2 ? dataScopeForm.value.dataScopeDeptIds : [],
    });
    message.success('分配成功');
    dataScopeModalVisible.value = false;
  } finally {
    dataScopeModalLoading.value = false;
  }
}
</script>

<template>
  <Card title="角色管理">
    <!-- 搜索表单 -->
    <Form layout="inline" class="mb-4" :model="searchForm">
      <FormItem label="角色名称">
        <Input v-model:value="searchForm.name" placeholder="请输入角色名称" allow-clear />
      </FormItem>
      <FormItem label="角色标识">
        <Input v-model:value="searchForm.code" placeholder="请输入角色标识" allow-clear />
      </FormItem>
      <FormItem label="状态">
        <Select v-model:value="searchForm.status" placeholder="请选择状态" allow-clear style="width: 120px">
          <Select.Option :value="0">正常</Select.Option>
          <Select.Option :value="1">停用</Select.Option>
        </Select>
      </FormItem>
      <FormItem>
        <Space>
          <Button type="primary" @click="handleSearch"><SearchOutlined /> 搜索</Button>
          <Button @click="handleReset"><ReloadOutlined /> 重置</Button>
        </Space>
      </FormItem>
    </Form>

    <div class="mb-4">
      <Button type="primary" @click="openModal()"><PlusOutlined /> 新增角色</Button>
    </div>

    <Table
      :columns="columns"
      :data-source="dataSource"
      :loading="loading"
      :pagination="{ current: pagination.pageNo, pageSize: pagination.pageSize, total, showSizeChanger: true, showTotal: (t: number) => `共 ${t} 条` }"
      row-key="id"
      @change="(p: any) => { pagination.pageNo = p.current; pagination.pageSize = p.pageSize; loadData(); }"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.dataIndex === 'status'">
          <Tag :color="record.status === 0 ? 'green' : 'red'">{{ record.status === 0 ? '正常' : '停用' }}</Tag>
        </template>
        <template v-if="column.key === 'operation'">
          <Space>
            <Button type="link" size="small" @click="openModal(record as SystemRoleApi.Role)">编辑</Button>
            <Button type="link" size="small" @click="openMenuModal(record as SystemRoleApi.Role)">分配权限</Button>
            <Button type="link" size="small" @click="openDataScopeModal(record as SystemRoleApi.Role)">数据权限</Button>
            <Button type="link" size="small" danger @click="handleDelete(record.id)">删除</Button>
          </Space>
        </template>
      </template>
    </Table>

    <!-- 新增/编辑 Modal -->
    <Modal v-model:open="modalVisible" :title="modalTitle" :confirm-loading="modalLoading" width="600px" @ok="handleModalOk" destroy-on-close>
      <Form :label-col="{ span: 5 }" :wrapper-col="{ span: 17 }" class="mt-4">
        <FormItem label="角色名称" required>
          <Input v-model:value="formData.name" placeholder="请输入角色名称" />
        </FormItem>
        <FormItem label="角色标识" required>
          <Input v-model:value="formData.code" placeholder="请输入角色标识" />
        </FormItem>
        <FormItem label="排序">
          <InputNumber v-model:value="formData.sort" :min="0" placeholder="请输入排序" class="w-full" />
        </FormItem>
        <FormItem label="状态">
          <RadioGroup v-model:value="formData.status">
            <Radio :value="0">正常</Radio>
            <Radio :value="1">停用</Radio>
          </RadioGroup>
        </FormItem>
        <FormItem label="备注">
          <Input.TextArea v-model:value="(formData as any).remark" placeholder="请输入备注" :rows="3" />
        </FormItem>
      </Form>
    </Modal>

    <!-- 分配菜单权限 Modal -->
    <Modal v-model:open="menuModalVisible" title="分配菜单权限" :confirm-loading="menuModalLoading" width="650px" @ok="handleMenuModalOk" destroy-on-close>
      <div style="max-height: 500px; overflow-y: auto;">
        <div class="mb-2" style="color: var(--color-text-secondary, #5c5c6f); font-size: 13px;">
          勾选父级目录将同时授予其下全部子菜单；保存前会列出本次授权变更明细。
        </div>
        <Tree
          v-model:checkedKeys="checkedMenuKeys"
          v-model:expandedKeys="expandedMenuKeys"
          :auto-expand-parent="autoExpandParent"
          :tree-data="menuTreeData as any"
          :field-names="menuTreeFieldNames"
          checkable
          @check="onMenuCheck"
          @expand="onMenuExpand as any"
        />
      </div>
    </Modal>

    <!-- 数据权限 Modal -->
    <Modal v-model:open="dataScopeModalVisible" title="数据权限" :confirm-loading="dataScopeModalLoading" width="550px" @ok="handleDataScopeModalOk" destroy-on-close>
      <Form :label-col="{ span: 5 }" :wrapper-col="{ span: 18 }" class="mt-4">
        <FormItem label="数据范围">
          <Select v-model:value="dataScopeForm.dataScope" :options="dataScopeOptions" />
        </FormItem>
        <FormItem v-if="dataScopeForm.dataScope === 2" label="指定部门">
          <div style="max-height: 360px; overflow-y: auto; border: 1px solid var(--color-border, #e5e6eb); border-radius: 6px; padding: 8px;">
            <Tree
              v-model:checkedKeys="dataScopeForm.dataScopeDeptIds"
              :tree-data="deptTreeData as any"
              :field-names="deptTreeFieldNames"
              checkable
              default-expand-all
              @check="onDeptCheck"
            />
          </div>
        </FormItem>
      </Form>
    </Modal>
  </Card>
</template>
