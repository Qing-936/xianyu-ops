<script setup lang="ts">
import { ref, onMounted, computed } from 'vue';
import {
  Card, Table, Button, Space, Tag, Modal, message, Form, Input,
  InputNumber, RadioGroup, Radio, Select, TreeSelect,
} from 'ant-design-vue';
import { PlusOutlined } from '@ant-design/icons-vue';
import { getDeptList, createDept, updateDept, deleteDept, getDept } from '#/api/system/dept';
import type { SystemDeptApi } from '#/api/system/dept';
import { getSimpleUserList } from '#/api/system/user';
import type { SystemUserApi } from '#/api/system/user';
import { formatDateTime } from '#/utils/format';

defineOptions({ name: 'SystemDept' });

const loading = ref(false);
const dataSource = ref<SystemDeptApi.Dept[]>([]);
const deptTreeData = ref<SystemDeptApi.Dept[]>([]);
/** 用户精简列表（用于负责人选择与展示） */
const userList = ref<SystemUserApi.User[]>([]);
const userMap = computed(() => new Map(userList.value.map((u) => [u.id, u.nickname])));

const columns = [
  { title: '部门名称', dataIndex: 'name', width: 200 },
  { title: '排序', dataIndex: 'sort', width: 80 },
  { title: '负责人', dataIndex: 'leaderUserId', width: 120 },
  { title: '状态', dataIndex: 'status', width: 100 },
  { title: '创建时间', dataIndex: 'createTime', width: 180, customRender: ({ text }: { text: string }) => formatDateTime(text) },
  { title: '操作', key: 'operation', width: 200 },
];

async function loadData() {
  loading.value = true;
  try {
    dataSource.value = await getDeptList();
    deptTreeData.value = dataSource.value;
  } finally {
    loading.value = false;
  }
}

/** 加载负责人候选用户 */
async function loadUsers() {
  userList.value = (await getSimpleUserList()) || [];
}

function handleDelete(id: number) {
  Modal.confirm({ title: '确认删除', content: '确定要删除该部门吗？', async onOk() { await deleteDept(id); message.success('删除成功'); loadData(); } });
}

onMounted(() => {
  loadData();
  loadUsers();
});

// ============ 新增/编辑 Modal ============
const modalVisible = ref(false);
const modalLoading = ref(false);
const formData = ref<Partial<SystemDeptApi.Dept>>({});
const FormItem = Form.Item;

const deptTreeSelectData = computed(() => {
  // 构建树形选择数据
  const list = dataSource.value;
  const map = new Map<number, any>();
  const roots: any[] = [{ id: 0, name: '顶级部门', children: [] }];
  for (const item of list) {
    map.set(item.id!, { id: item.id, name: item.name, children: [] });
  }
  for (const item of list) {
    const node = map.get(item.id!)!;
    if (item.parentId && map.has(item.parentId)) {
      map.get(item.parentId)!.children.push(node);
    } else {
      roots[0].children.push(node);
    }
  }
  return roots;
});



function openModal(record?: SystemDeptApi.Dept, parentId?: number) {
  if (record?.id) {
    // 编辑
    getDept(record.id!).then((detail) => {
      formData.value = { ...detail };
      modalVisible.value = true;
    });
  } else {
    // 新增（可能指定上级部门）
    formData.value = { status: 0, sort: 0, parentId: parentId ?? 0 };
    modalVisible.value = true;
  }
}

async function handleModalOk() {
  modalLoading.value = true;
  try {
    if (formData.value?.id) {
      await updateDept(formData.value as SystemDeptApi.Dept);
    } else {
      await createDept(formData.value as SystemDeptApi.Dept);
    }
    message.success('操作成功');
    modalVisible.value = false;
    loadData();
  } finally {
    modalLoading.value = false;
  }
}
</script>

<template>
  <Card title="部门管理">
    <div class="mb-4">
      <Button type="primary" @click="openModal()"><PlusOutlined /> 新增部门</Button>
    </div>

    <Table :columns="columns" :data-source="dataSource" :loading="loading" row-key="id" :pagination="false" default-expand-all-rows>
      <template #bodyCell="{ column, record }">
        <template v-if="column.dataIndex === 'leaderUserId'">
          {{ userMap.get(record.leaderUserId) || '-' }}
        </template>
        <template v-if="column.dataIndex === 'status'">
          <Tag :color="record.status === 0 ? 'green' : 'red'">{{ record.status === 0 ? '正常' : '停用' }}</Tag>
        </template>
        <template v-if="column.key === 'operation'">
          <Space>
            <Button type="link" size="small" @click="openModal(undefined, record.id)">新增下级</Button>
            <Button type="link" size="small" @click="openModal(record as SystemDeptApi.Dept)">编辑</Button>
            <Button type="link" size="small" danger @click="handleDelete(record.id)">删除</Button>
          </Space>
        </template>
      </template>
    </Table>

    <!-- 新增/编辑 Modal -->
    <Modal v-model:open="modalVisible" :title="formData?.id ? '编辑部门' : '新增部门'" :confirm-loading="modalLoading" width="600px" @ok="handleModalOk" destroy-on-close>
      <Form :label-col="{ span: 5 }" :wrapper-col="{ span: 17 }" class="mt-4">
        <FormItem label="上级部门">
          <TreeSelect
            v-model:value="formData.parentId"
            :tree-data="deptTreeSelectData"
            :field-names="{ label: 'name', value: 'id', children: 'children' }"
            placeholder="请选择上级部门"
            tree-default-expand-all
            allow-clear
          />
        </FormItem>
        <FormItem label="部门名称" required>
          <Input v-model:value="formData.name" placeholder="请输入部门名称" />
        </FormItem>
        <FormItem label="排序">
          <InputNumber v-model:value="formData.sort" :min="0" placeholder="请输入排序" class="w-full" />
        </FormItem>
        <FormItem label="负责人">
          <Select
            v-model:value="formData.leaderUserId"
            placeholder="请选择负责人"
            allow-clear
            show-search
            :filter-option="(input: string, option: any) => (option?.label ?? '').toLowerCase().includes(input.toLowerCase())"
            :options="userList.map((u) => ({ label: u.nickname, value: u.id }))"
          />
        </FormItem>
        <FormItem label="联系电话">
          <Input v-model:value="formData.phone" placeholder="请输入联系电话" />
        </FormItem>
        <FormItem label="邮箱">
          <Input v-model:value="formData.email" placeholder="请输入邮箱" />
        </FormItem>
        <FormItem label="状态">
          <RadioGroup v-model:value="formData.status">
            <Radio :value="0">正常</Radio>
            <Radio :value="1">停用</Radio>
          </RadioGroup>
        </FormItem>
      </Form>
    </Modal>
  </Card>
</template>