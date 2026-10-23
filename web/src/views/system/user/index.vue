<script setup lang="ts">
import { ref, onMounted, computed } from 'vue';
import {
  Card, Table, Button, Space, Modal, message, Form, Input,
  Select, Radio, TreeSelect, Switch, DatePicker,
} from 'ant-design-vue';
import { PlusOutlined, SearchOutlined, ReloadOutlined } from '@ant-design/icons-vue';
import {
  getUserPage, createUser, updateUser, deleteUser, updateUserStatus, resetUserPassword, getUser,
} from '#/api/system/user';
import { getSimpleDeptList } from '#/api/system/dept';
import { getSimplePostList } from '#/api/system/post';
import { getSimpleRoleList } from '#/api/system/role';
import { assignUserRole, getUserRoleList } from '#/api/system/permission';
import type { SystemUserApi } from '#/api/system/user';
import type { SystemDeptApi } from '#/api/system/dept';
import type { SystemPostApi } from '#/api/system/post';
import type { SystemRoleApi } from '#/api/system/role';
import { formatDateTime } from '#/utils/format';
import DeptTree from './components/DeptTree.vue';

defineOptions({ name: 'SystemUser' });

const RangePicker = DatePicker.RangePicker;

// ============ 字典选项 ============
const sexOptions = [
  { label: '男', value: 1 },
  { label: '女', value: 2 },
  { label: '未设置', value: 0 },
];
const statusOptions = [
  { label: '正常', value: 0 },
  { label: '停用', value: 1 },
];

// ============ 列表相关 ============
const loading = ref(false);
const dataSource = ref<SystemUserApi.User[]>([]);
const total = ref(0);
const pagination = ref({ pageNo: 1, pageSize: 20 });

// 搜索表单（deptId 由左侧部门树选择，createTime 为创建时间范围）
const searchForm = ref({
  username: '',
  mobile: '',
  status: undefined as number | undefined,
  deptId: undefined as number | undefined,
  createTime: undefined as [string, string] | undefined,
});
/** 当前选中的部门名称（用于列表标题展示） */
const selectedDeptName = ref('');
const deptTreeRef = ref<InstanceType<typeof DeptTree>>();

const columns = [
  { title: '用户编号', dataIndex: 'id', width: 100 },
  { title: '用户名称', dataIndex: 'username', width: 120 },
  { title: '用户昵称', dataIndex: 'nickname', width: 120 },
  { title: '部门', dataIndex: 'deptId', width: 120 },
  { title: '手机号码', dataIndex: 'mobile', width: 130 },
  { title: '状态', dataIndex: 'status', width: 100 },
  { title: '创建时间', dataIndex: 'createTime', width: 180, customRender: ({ text }: { text: string }) => formatDateTime(text) },
  { title: '操作', key: 'operation', width: 280, fixed: 'right' as const },
];

async function loadData() {
  loading.value = true;
  try {
    const res = await getUserPage({ ...pagination.value, ...searchForm.value });
    dataSource.value = res.list || [];
    total.value = res.total || 0;
  } finally {
    loading.value = false;
  }
}

function handleSearch() {
  pagination.value.pageNo = 1;
  loadData();
}

function handleReset() {
  searchForm.value = { username: '', mobile: '', status: undefined, deptId: undefined, createTime: undefined };
  selectedDeptName.value = '';
  deptTreeRef.value?.clearSelection();
  handleSearch();
}

/** 左侧部门树选中/取消选中 */
function handleDeptSelect(deptId: number | undefined, deptName?: string) {
  searchForm.value.deptId = deptId;
  selectedDeptName.value = deptName || '';
  pagination.value.pageNo = 1;
  loadData();
}

function handleDelete(id: number) {
  Modal.confirm({
    title: '确认删除',
    content: '确定要删除该用户吗？',
    async onOk() {
      await deleteUser(id);
      message.success('删除成功');
      loadData();
    },
  });
}

/** 状态开关 */
async function handleStatusChange(checked: boolean, record: SystemUserApi.User) {
  const newStatus = checked ? 0 : 1;
  try {
    await updateUserStatus(record.id!, newStatus);
    message.success('修改成功');
    loadData();
  } catch {
    // 失败时不刷新，保持原状态
  }
}

onMounted(loadData);

// ============ 新增/编辑 Modal ============
const modalVisible = ref(false);
const modalLoading = ref(false);
const modalTitle = computed(() => (formData.value?.id ? '编辑用户' : '新增用户'));
const formData = ref<Partial<SystemUserApi.User> & { password?: string }>({});
const deptTree = ref<SystemDeptApi.Dept[]>([]);
const postList = ref<SystemPostApi.Post[]>([]);
const roleList = ref<SystemRoleApi.Role[]>([]);

const FormItem = Form.Item;
const TextArea = Input.TextArea;

/** 将扁平部门列表构建成树 */
function buildDeptTree(list: SystemDeptApi.Dept[]): any[] {
  const map = new Map<number, any>();
  const roots: any[] = [];
  for (const item of list) {
    map.set(item.id!, { ...item, key: item.id, value: item.id, title: item.name, children: [] });
  }
  for (const item of list) {
    const node = map.get(item.id!);
    if (item.parentId && map.has(item.parentId)) {
      map.get(item.parentId).children.push(node);
    } else {
      roots.push(node);
    }
  }
  return roots;
}

const deptTreeData = computed(() => buildDeptTree(deptTree.value));

/** 部门层级路径映射（如：青泰 / 技术部 / 开发组） */
const deptPathMap = computed(() => {
  const map = new Map<number, string>();
  const list = deptTree.value;
  // 构建 id → item 映射
  const idMap = new Map<number, SystemDeptApi.Dept>();
  for (const item of list) {
    idMap.set(item.id!, item);
  }
  // 对每个部门，向上追溯到根节点，构建完整路径
  for (const item of list) {
    const parts: string[] = [];
    let current: SystemDeptApi.Dept | undefined = item;
    while (current) {
      parts.unshift(current.name);
      current = current.parentId ? idMap.get(current.parentId) : undefined;
    }
    map.set(item.id!, parts.join(' / '));
  }
  return map;
});

async function openModal(record?: SystemUserApi.User) {
  // 加载部门、岗位、角色选项
  const [deptRes, postRes, roleRes] = await Promise.all([getSimpleDeptList(), getSimplePostList(), getSimpleRoleList()]);
  deptTree.value = deptRes || [];
  postList.value = postRes || [];
  roleList.value = roleRes || [];

  if (record?.id) {
    // 编辑：加载详情
    const detail = await getUser(record.id!);
    formData.value = { ...detail };
  } else {
    formData.value = { status: 0, sex: 1 };
  }
  modalVisible.value = true;
}

async function handleModalOk() {
  modalLoading.value = true;
  try {
    if (formData.value?.id) {
      await updateUser(formData.value as SystemUserApi.User);
    } else {
      await createUser(formData.value as SystemUserApi.User);
    }
    message.success('操作成功');
    modalVisible.value = false;
    loadData();
  } finally {
    modalLoading.value = false;
  }
}

// ============ 重置密码 Modal ============
const resetPwdVisible = ref(false);
const resetPwdLoading = ref(false);
const resetPwdData = ref<{ id?: number; username: string; password: string; confirmPassword: string }>({ username: '', password: '', confirmPassword: '' });

function openResetPwd(record: SystemUserApi.User) {
  resetPwdData.value = { id: record.id, username: record.username, password: '', confirmPassword: '' };
  resetPwdVisible.value = true;
}

async function handleResetPwdOk() {
  const { password, confirmPassword } = resetPwdData.value;
  if (!password) {
    message.warning('请输入新密码');
    return;
  }
  if (password.length < 5 || password.length > 20) {
    message.warning('密码长度必须在 5-20 个字符之间');
    return;
  }
  if (password !== confirmPassword) {
    message.warning('新密码和确认密码不一致');
    return;
  }
  resetPwdLoading.value = true;
  try {
    await resetUserPassword(resetPwdData.value.id!, resetPwdData.value.password);
    message.success('重置成功');
    resetPwdVisible.value = false;
  } finally {
    resetPwdLoading.value = false;
  }
}

// ============ 分配角色 Modal ============
const assignRoleVisible = ref(false);
const assignRoleLoading = ref(false);
const assignRoleData = ref<{ id?: number; username: string; nickname: string; roleIds: number[] }>({
  username: '', nickname: '', roleIds: [],
});

async function openAssignRole(record: SystemUserApi.User) {
  // 并行加载角色列表和用户已有角色
  const [roleRes, roleIds] = await Promise.all([getSimpleRoleList(), getUserRoleList(record.id!)]);
  roleList.value = roleRes || [];
  assignRoleData.value = { id: record.id, username: record.username, nickname: record.nickname, roleIds };
  assignRoleVisible.value = true;
}

async function handleAssignRoleOk() {
  assignRoleLoading.value = true;
  try {
    await assignUserRole({ userId: assignRoleData.value.id!, roleIds: assignRoleData.value.roleIds });
    message.success('分配成功');
    assignRoleVisible.value = false;
  } finally {
    assignRoleLoading.value = false;
  }
}
</script>

<template>
  <div class="user-page">
    <!-- 左侧部门树 -->
    <Card title="部门" class="user-dept-card">
      <DeptTree ref="deptTreeRef" @select="handleDeptSelect" />
    </Card>

    <!-- 右侧用户列表 -->
    <Card :title="selectedDeptName ? `用户管理（${selectedDeptName}）` : '用户管理'" class="user-main-card">
    <!-- 搜索表单 -->
    <Form layout="inline" class="mb-4" :model="searchForm">
      <FormItem label="用户名称">
        <Input v-model:value="searchForm.username" placeholder="请输入用户名称" allow-clear />
      </FormItem>
      <FormItem label="手机号码">
        <Input v-model:value="searchForm.mobile" placeholder="请输入手机号码" allow-clear />
      </FormItem>
      <FormItem label="状态">
        <Select v-model:value="searchForm.status" placeholder="请选择状态" allow-clear style="width: 120px"
          :options="statusOptions" />
      </FormItem>
      <FormItem label="创建时间">
        <RangePicker
          v-model:value="searchForm.createTime"
          show-time
          format="YYYY-MM-DD HH:mm:ss"
          value-format="YYYY-MM-DD HH:mm:ss"
          :placeholder="['开始时间', '结束时间']"
        />
      </FormItem>
      <FormItem>
        <Space>
          <Button type="primary" @click="handleSearch"><SearchOutlined /> 搜索</Button>
          <Button @click="handleReset"><ReloadOutlined /> 重置</Button>
        </Space>
      </FormItem>
    </Form>

    <!-- 操作栏 -->
    <div class="mb-4">
      <Button type="primary" @click="openModal()"><PlusOutlined /> 新增用户</Button>
    </div>

    <!-- 表格 -->
    <Table
      :columns="columns"
      :data-source="dataSource"
      :loading="loading"
      :pagination="{ current: pagination.pageNo, pageSize: pagination.pageSize, total, showSizeChanger: true, showTotal: (t: number) => `共 ${t} 条` }"
      row-key="id"
      @change="(p: any) => { pagination.pageNo = p.current; pagination.pageSize = p.pageSize; loadData(); }"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.dataIndex === 'deptId'">
          {{ deptPathMap.get(record.deptId) || record.deptId }}
        </template>
        <template v-if="column.dataIndex === 'status'">
          <Switch
            :checked="record.status === 0"
            checked-children="正常"
            un-checked-children="停用"
            @change="(checked: any) => handleStatusChange(!!checked, record as SystemUserApi.User)"
          />
        </template>
        <template v-if="column.key === 'operation'">
          <Space>
            <Button type="link" size="small" @click="openModal(record as SystemUserApi.User)">编辑</Button>
            <Button type="link" size="small" @click="openAssignRole(record as SystemUserApi.User)">分配角色</Button>
            <Button type="link" size="small" @click="openResetPwd(record as SystemUserApi.User)">重置密码</Button>
            <Button type="link" size="small" danger @click="handleDelete(record.id)">删除</Button>
          </Space>
        </template>
      </template>
    </Table>

    <!-- 新增/编辑 Modal -->
    <Modal
      v-model:open="modalVisible"
      :title="modalTitle"
      :confirm-loading="modalLoading"
      width="600px"
      @ok="handleModalOk"
      destroy-on-close
    >
      <Form :label-col="{ span: 5 }" :wrapper-col="{ span: 17 }" class="mt-4">
        <FormItem label="用户名称" required>
          <Input v-model:value="formData.username" placeholder="请输入用户名称" :disabled="!!formData.id" />
        </FormItem>
        <FormItem v-if="!formData.id" label="用户密码" required>
          <Input.Password v-model:value="formData.password" placeholder="请输入密码" />
        </FormItem>
        <FormItem label="用户昵称" required>
          <Input v-model:value="formData.nickname" placeholder="请输入用户昵称" />
        </FormItem>
        <FormItem label="归属部门">
          <TreeSelect
            v-model:value="formData.deptId"
            placeholder="请选择归属部门"
            allow-clear
            tree-default-expand-all
            :tree-data="deptTreeData"
            tree-line
          />
        </FormItem>
        <FormItem label="岗位">
          <Select
            v-model:value="formData.postIds"
            placeholder="请选择岗位"
            allow-clear
            mode="multiple"
            :options="postList.map(p => ({ label: p.name, value: p.id }))"
          />
        </FormItem>
        <FormItem label="邮箱">
          <Input v-model:value="formData.email" placeholder="请输入邮箱" />
        </FormItem>
        <FormItem label="手机号码">
          <Input v-model:value="formData.mobile" placeholder="请输入手机号码" />
        </FormItem>
        <FormItem label="用户性别">
          <Radio.Group v-model:value="formData.sex" button-style="solid" option-type="button" :options="sexOptions" />
        </FormItem>
        <FormItem label="用户状态">
          <Radio.Group v-model:value="formData.status" button-style="solid" option-type="button" :options="statusOptions" />
        </FormItem>
        <FormItem label="备注">
          <TextArea v-model:value="formData.remark" placeholder="请输入备注" :rows="3" />
        </FormItem>
      </Form>
    </Modal>

    <!-- 重置密码 Modal -->
    <Modal
      v-model:open="resetPwdVisible"
      title="重置密码"
      :confirm-loading="resetPwdLoading"
      @ok="handleResetPwdOk"
      destroy-on-close
    >
      <Form :label-col="{ span: 5 }" :wrapper-col="{ span: 17 }" class="mt-4">
        <FormItem label="用户名">
          <Input :value="resetPwdData.username" disabled />
        </FormItem>
        <FormItem label="新密码" required>
          <Input.Password v-model:value="resetPwdData.password" placeholder="请输入新密码（5-20 个字符）" />
        </FormItem>
        <FormItem label="确认密码" required>
          <Input.Password v-model:value="resetPwdData.confirmPassword" placeholder="请再次输入新密码" />
        </FormItem>
      </Form>
    </Modal>

    <!-- 分配角色 Modal -->
    <Modal
      v-model:open="assignRoleVisible"
      title="分配角色"
      :confirm-loading="assignRoleLoading"
      @ok="handleAssignRoleOk"
      destroy-on-close
    >
      <Form :label-col="{ span: 5 }" :wrapper-col="{ span: 17 }" class="mt-4">
        <FormItem label="用户名称">
          <Input :value="assignRoleData.username" disabled />
        </FormItem>
        <FormItem label="用户昵称">
          <Input :value="assignRoleData.nickname" disabled />
        </FormItem>
        <FormItem label="角色">
          <Select
            v-model:value="assignRoleData.roleIds"
            placeholder="请选择角色"
            allow-clear
            mode="multiple"
            :options="roleList.map(r => ({ label: r.name, value: r.id }))"
          />
        </FormItem>
      </Form>
    </Modal>
    </Card>
  </div>
</template>

<style scoped>
.user-page {
  display: flex;
  gap: 16px;
  align-items: flex-start;
}

.user-dept-card {
  width: 240px;
  flex-shrink: 0;
}

.user-main-card {
  flex: 1;
  min-width: 0;
}

/* 响应式：移动端（<768px）上下堆叠 */
@media (max-width: 767px) {
  .user-page {
    flex-direction: column;
  }

  .user-dept-card {
    width: 100%;
  }
}
</style>
