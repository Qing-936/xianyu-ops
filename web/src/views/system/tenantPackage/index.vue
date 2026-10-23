<script setup lang="ts">
import { ref, onMounted, computed } from 'vue';
import {
  Card, Table, Button, Space, Modal, message, Form, Input,
  Select, RadioGroup, Radio, Tree, Checkbox, DatePicker,
} from 'ant-design-vue';
import { PlusOutlined, SearchOutlined, ReloadOutlined, DeleteOutlined } from '@ant-design/icons-vue';
import {
  getTenantPackagePage, getTenantPackage, createTenantPackage,
  updateTenantPackage, deleteTenantPackage, deleteTenantPackageList,
} from '#/api/system/tenant-package';
import type { SystemTenantPackageApi } from '#/api/system/tenant-package';
import { getMenuList } from '#/api/system/menu';
import type { SystemMenuApi } from '#/api/system/menu';
import { handleTree, collectTreeIds } from '#/utils/tree';
import { formatDateTime } from '#/utils/format';
import { useDictStore } from '#/store/dict';
import { usePermissionStore } from '#/store/permission';

defineOptions({ name: 'SystemTenantPackage' });

const FormItem = Form.Item;
const RangePicker = DatePicker.RangePicker;
const dictStore = useDictStore();
const permissionStore = usePermissionStore();

/** common_status 字典选项（0 正常 / 1 停用） */
const statusOptions = computed(() =>
  dictStore.getDictData('common_status').map((d) => ({ label: d.label, value: Number(d.value) })),
);

// ============ 列表相关 ============
const loading = ref(false);
const dataSource = ref<SystemTenantPackageApi.TenantPackage[]>([]);
const total = ref(0);
const pagination = ref({ pageNo: 1, pageSize: 20 });
const searchForm = ref<{
  name: string;
  status: number | undefined;
  createTime: [string, string] | undefined;
}>({ name: '', status: undefined, createTime: undefined });

const columns = [
  { title: '套餐编号', dataIndex: 'id', key: 'id', width: 90 },
  { title: '套餐名称', dataIndex: 'name', key: 'name', width: 180 },
  { title: '状态', dataIndex: 'status', key: 'status', width: 100 },
  { title: '备注', dataIndex: 'remark', key: 'remark', ellipsis: true },
  { title: '创建时间', dataIndex: 'createTime', key: 'createTime', width: 180, customRender: ({ text }: { text: string }) => formatDateTime(text) },
  { title: '操作', key: 'operation', width: 160, fixed: 'right' as const },
];

const selectedRowKeys = ref<number[]>([]);
const rowSelection = computed(() => ({
  selectedRowKeys: selectedRowKeys.value,
  onChange: (keys: (number | string)[]) => { selectedRowKeys.value = keys as number[]; },
}));

async function loadData() {
  loading.value = true;
  try {
    const res = await getTenantPackagePage({ ...pagination.value, ...searchForm.value });
    dataSource.value = res.list || [];
    total.value = res.total || 0;
  } finally {
    loading.value = false;
  }
}

function handleSearch() { pagination.value.pageNo = 1; loadData(); }
function handleReset() { searchForm.value = { name: '', status: undefined, createTime: undefined }; handleSearch(); }

function handleDelete(id: number) {
  Modal.confirm({
    title: '确认删除',
    content: '确定要删除该租户套餐吗？',
    async onOk() {
      await deleteTenantPackage(id);
      message.success('删除成功');
      loadData();
    },
  });
}

function handleBatchDelete() {
  if (selectedRowKeys.value.length === 0) return;
  Modal.confirm({
    title: '确认批量删除',
    content: `确定要删除选中的 ${selectedRowKeys.value.length} 个租户套餐吗？`,
    async onOk() {
      await deleteTenantPackageList(selectedRowKeys.value);
      message.success('删除成功');
      selectedRowKeys.value = [];
      loadData();
    },
  });
}

onMounted(loadData);

// ============ 新增/编辑 Modal ============
const modalVisible = ref(false);
const modalLoading = ref(false);
const modalTitle = computed(() => (formData.value?.id ? '编辑租户套餐' : '新增租户套餐'));
const formData = ref<Partial<SystemTenantPackageApi.TenantPackage>>({});

// ---- 菜单树 ----
const menuTreeLoading = ref(false);
const menuTreeData = ref<SystemMenuApi.Menu[]>([]);
const checkedMenuKeys = ref<number[]>([]);
const expandedMenuKeys = ref<number[]>([]);
const checkAll = ref(false);
const expandAll = ref(false);
const menuTreeFieldNames = { title: 'name', key: 'id', children: 'children' };

async function loadMenuTree() {
  if (menuTreeData.value.length > 0) return;
  menuTreeLoading.value = true;
  try {
    const menus = await getMenuList();
    menuTreeData.value = handleTree(menus || []);
  } finally {
    menuTreeLoading.value = false;
  }
}

/** checkStrictly 模式下 checkedKeys 为 { checked, halfChecked } 对象，需解包 */
function onMenuCheck(checked: { checked: number[]; halfChecked: number[] } | number[]) {
  checkedMenuKeys.value = Array.isArray(checked) ? checked : checked.checked;
  checkAll.value = checkedMenuKeys.value.length > 0
    && checkedMenuKeys.value.length === collectTreeIds(menuTreeData.value).length;
}

function onCheckAllChange(e: { target: { checked: boolean } }) {
  checkedMenuKeys.value = e.target.checked ? collectTreeIds(menuTreeData.value) : [];
}

function onExpandAllChange(e: { target: { checked: boolean } }) {
  expandedMenuKeys.value = e.target.checked ? collectTreeIds(menuTreeData.value) : [];
}

async function openModal(record?: SystemTenantPackageApi.TenantPackage) {
  checkAll.value = false;
  expandAll.value = false;
  expandedMenuKeys.value = [];
  await loadMenuTree();
  if (record?.id) {
    const detail = await getTenantPackage(record.id);
    formData.value = { ...detail };
    checkedMenuKeys.value = detail.menuIds || [];
  } else {
    formData.value = { status: 0, menuIds: [] };
    checkedMenuKeys.value = [];
  }
  modalVisible.value = true;
}

async function handleModalOk() {
  if (!formData.value.name) { message.warning('请输入套餐名称'); return; }
  if (checkedMenuKeys.value.length === 0) { message.warning('请至少勾选一个菜单'); return; }
  modalLoading.value = true;
  try {
    const data = { ...formData.value, menuIds: checkedMenuKeys.value } as SystemTenantPackageApi.TenantPackage;
    if (data.id) {
      await updateTenantPackage(data);
    } else {
      await createTenantPackage(data);
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
  <Card title="租户套餐">
    <!-- 搜索表单 -->
    <Form layout="inline" class="mb-4" :model="searchForm">
      <FormItem label="套餐名称">
        <Input v-model:value="searchForm.name" placeholder="请输入套餐名称" allow-clear />
      </FormItem>
      <FormItem label="状态">
        <Select v-model:value="searchForm.status" placeholder="请选择状态" allow-clear style="width: 120px" :options="statusOptions" />
      </FormItem>
      <FormItem label="创建时间">
        <RangePicker
          v-model:value="searchForm.createTime"
          show-time
          format="YYYY-MM-DD HH:mm:ss"
          value-format="YYYY-MM-DD HH:mm:ss"
        />
      </FormItem>
      <FormItem>
        <Space>
          <Button type="primary" @click="handleSearch"><SearchOutlined /> 搜索</Button>
          <Button @click="handleReset"><ReloadOutlined /> 重置</Button>
        </Space>
      </FormItem>
    </Form>

    <!-- 工具栏 -->
    <div class="mb-4">
      <Space>
        <Button v-if="permissionStore.hasPermission('system:tenant-package:create')" type="primary" @click="openModal()">
          <PlusOutlined /> 新增套餐
        </Button>
        <Button
          v-if="permissionStore.hasPermission('system:tenant-package:delete')"
          danger
          :disabled="selectedRowKeys.length === 0"
          @click="handleBatchDelete"
        >
          <DeleteOutlined /> 批量删除
        </Button>
      </Space>
    </div>

    <Table
      :columns="columns"
      :data-source="dataSource"
      :loading="loading"
      :row-selection="rowSelection"
      :pagination="{ current: pagination.pageNo, pageSize: pagination.pageSize, total, showSizeChanger: true, showTotal: (t: number) => `共 ${t} 条` }"
      row-key="id"
      :scroll="{ x: 900 }"
      @change="(p: any) => { pagination.pageNo = p.current; pagination.pageSize = p.pageSize; loadData(); }"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'status'">
          <DictTag type="common_status" :value="record.status" />
        </template>
        <template v-if="column.key === 'operation'">
          <Space>
            <Button v-if="permissionStore.hasPermission('system:tenant-package:update')" type="link" size="small" @click="openModal(record as SystemTenantPackageApi.TenantPackage)">编辑</Button>
            <Button v-if="permissionStore.hasPermission('system:tenant-package:delete')" type="link" size="small" danger @click="handleDelete(record.id)">删除</Button>
          </Space>
        </template>
      </template>
    </Table>

    <!-- 新增/编辑 Modal -->
    <Modal v-model:open="modalVisible" :title="modalTitle" :confirm-loading="modalLoading" width="650px" @ok="handleModalOk" destroy-on-close>
      <Form :label-col="{ span: 4 }" :wrapper-col="{ span: 19 }" class="mt-4">
        <FormItem label="套餐名称" required>
          <Input v-model:value="formData.name" placeholder="请输入套餐名称" />
        </FormItem>
        <FormItem label="菜单权限" required>
          <Space class="mb-2">
            <Checkbox v-model:checked="checkAll" @change="onCheckAllChange as any">全选</Checkbox>
            <Checkbox v-model:checked="expandAll" @change="onExpandAllChange as any">全部展开</Checkbox>
          </Space>
          <div style="max-height: 400px; overflow-y: auto; border: 1px solid var(--color-border, #e5e6eb); border-radius: 6px; padding: 8px;">
            <Tree
              v-if="!menuTreeLoading"
              :checked-keys="checkedMenuKeys"
              v-model:expanded-keys="expandedMenuKeys"
              :tree-data="menuTreeData as any"
              :field-names="menuTreeFieldNames"
              checkable
              check-strictly
              @check="onMenuCheck as any"
            />
            <div v-else style="text-align: center; padding: 24px; color: var(--color-text-muted, #8b8b9e);">菜单加载中...</div>
          </div>
        </FormItem>
        <FormItem label="状态">
          <RadioGroup v-model:value="formData.status">
            <Radio :value="0">正常</Radio>
            <Radio :value="1">停用</Radio>
          </RadioGroup>
        </FormItem>
        <FormItem label="备注">
          <Input.TextArea v-model:value="formData.remark" placeholder="请输入备注" :rows="3" />
        </FormItem>
      </Form>
    </Modal>
  </Card>
</template>
