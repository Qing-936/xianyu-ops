<script setup lang="ts">
import { ref, onMounted, computed } from 'vue';
import {
  Card, Table, Button, Space, Modal, message, Form, Input,
  Select, InputNumber, RadioGroup, Radio, DatePicker, Alert,
} from 'ant-design-vue';
import type { Rule } from 'ant-design-vue/es/form';
import { PlusOutlined, SearchOutlined, ReloadOutlined, DeleteOutlined, DownloadOutlined } from '@ant-design/icons-vue';
import {
  getTenantPage, getTenant, createTenant, updateTenant,
  deleteTenant, deleteTenantList, exportTenantExcel,
} from '#/api/system/tenant';
import type { SystemTenantApi } from '#/api/system/tenant';
import { getSimpleTenantPackageList } from '#/api/system/tenant-package';
import type { SystemTenantPackageApi } from '#/api/system/tenant-package';
import { formatDateTime } from '#/utils/format';
import { useDictStore } from '#/store/dict';
import { usePermissionStore } from '#/store/permission';

defineOptions({ name: 'SystemTenant' });

const FormItem = Form.Item;
const RangePicker = DatePicker.RangePicker;
const dictStore = useDictStore();
const permissionStore = usePermissionStore();

/** common_status 字典选项（0 正常 / 1 停用） */
const statusOptions = computed(() =>
  dictStore.getDictData('common_status').map((d) => ({ label: d.label, value: Number(d.value) })),
);

// ============ 租户套餐（列表展示映射 + 表单下拉） ============
const packageList = ref<SystemTenantPackageApi.TenantPackageSimple[]>([]);
const packageMap = computed(() => new Map(packageList.value.map((p) => [p.id, p.name])));
const packageOptions = computed(() => packageList.value.map((p) => ({ label: p.name, value: p.id })));

async function loadPackageList() {
  try {
    packageList.value = (await getSimpleTenantPackageList()) || [];
  } catch {
    // 套餐列表加载失败不阻塞页面
  }
}

/** 租户套餐列展示：0 = 系统租户，否则映射套餐名 */
function renderPackage(packageId: number | undefined): string {
  if (packageId === 0) return '系统租户';
  if (packageId === undefined || packageId === null) return '-';
  return packageMap.value.get(packageId) ?? `套餐(${packageId})`;
}

// ============ 列表相关 ============
const loading = ref(false);
const dataSource = ref<SystemTenantApi.Tenant[]>([]);
const total = ref(0);
const pagination = ref({ pageNo: 1, pageSize: 20 });
const searchForm = ref<{
  name: string;
  contactName: string;
  contactMobile: string;
  status: number | undefined;
  createTime: [string, string] | undefined;
}>({ name: '', contactName: '', contactMobile: '', status: undefined, createTime: undefined });

const columns = [
  { title: '租户编号', dataIndex: 'id', key: 'id', width: 90 },
  { title: '租户名称', dataIndex: 'name', key: 'name', width: 150 },
  { title: '租户套餐', dataIndex: 'packageId', key: 'packageId', width: 130 },
  { title: '联系人', dataIndex: 'contactName', key: 'contactName', width: 110 },
  { title: '联系手机', dataIndex: 'contactMobile', key: 'contactMobile', width: 130 },
  { title: '账号额度', dataIndex: 'accountCount', key: 'accountCount', width: 90 },
  { title: '过期时间', dataIndex: 'expireTime', key: 'expireTime', width: 170, customRender: ({ text }: { text: number }) => formatDateTime(text) },
  { title: '绑定域名', dataIndex: 'websites', key: 'websites', width: 200, ellipsis: true, customRender: ({ text }: { text: string[] | undefined }) => (text || []).join(', ') || '-' },
  { title: '租户状态', dataIndex: 'status', key: 'status', width: 100 },
  { title: '创建时间', dataIndex: 'createTime', key: 'createTime', width: 170, customRender: ({ text }: { text: string }) => formatDateTime(text) },
  { title: '操作', key: 'operation', width: 140, fixed: 'right' as const },
];

const selectedRowKeys = ref<number[]>([]);
const rowSelection = computed(() => ({
  selectedRowKeys: selectedRowKeys.value,
  onChange: (keys: (number | string)[]) => { selectedRowKeys.value = keys as number[]; },
}));

async function loadData() {
  loading.value = true;
  try {
    const res = await getTenantPage({ ...pagination.value, ...searchForm.value });
    dataSource.value = res.list || [];
    total.value = res.total || 0;
  } finally {
    loading.value = false;
  }
}

function handleSearch() { pagination.value.pageNo = 1; loadData(); }
function handleReset() {
  searchForm.value = { name: '', contactName: '', contactMobile: '', status: undefined, createTime: undefined };
  handleSearch();
}

function handleDelete(id: number) {
  Modal.confirm({
    title: '确认删除',
    content: '确定要删除该租户吗？',
    async onOk() {
      await deleteTenant(id);
      message.success('删除成功');
      loadData();
    },
  });
}

function handleBatchDelete() {
  if (selectedRowKeys.value.length === 0) return;
  Modal.confirm({
    title: '确认批量删除',
    content: `确定要删除选中的 ${selectedRowKeys.value.length} 个租户吗？`,
    async onOk() {
      await deleteTenantList(selectedRowKeys.value);
      message.success('删除成功');
      selectedRowKeys.value = [];
      loadData();
    },
  });
}

// ============ 导出 Excel ============
const exportLoading = ref(false);

async function handleExport() {
  exportLoading.value = true;
  try {
    const resp = await exportTenantExcel({ ...searchForm.value });
    // 响应拦截器对 blob 返回完整 AxiosResponse，需取 .data
    const blob = resp?.data instanceof Blob ? resp.data : (resp as unknown as Blob);
    // 后端 JSON 错误被包装成 blob 的情况
    if (blob instanceof Blob && blob.type?.includes('application/json')) {
      const err = JSON.parse(await blob.text());
      message.error(err.msg || '导出失败');
      return;
    }
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = '租户.xls';
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
  } catch {
    message.error('导出失败，请重试');
  } finally {
    exportLoading.value = false;
  }
}

onMounted(() => {
  loadData();
  loadPackageList();
});

// ============ 新增/编辑 Modal ============
const modalVisible = ref(false);
const modalLoading = ref(false);
const modalTitle = computed(() => (formData.value?.id ? '编辑租户' : '新增租户'));
const formData = ref<Partial<SystemTenantApi.Tenant>>({});
/** Form 实例的 validate 未在组件类型上暴露，这里按实际运行时能力声明 */
const formRef = ref<{ validate: () => Promise<unknown> }>();

/** 域名格式（与后端 get-by-website 的 @Pattern 一致，勿带协议/路径） */
const DOMAIN_PATTERN = /^[a-zA-Z0-9.-]+(:\d{1,5})?$/;

const formRules: Record<string, Rule[]> = {
  name: [{ required: true, message: '请输入租户名称' }],
  packageId: [{ required: true, message: '请选择租户套餐' }],
  contactName: [{ required: true, message: '请输入联系人' }],
  contactMobile: [{ pattern: /^1[3-9]\d{9}$/, message: '请输入正确的手机号' }],
  username: [{ required: true, message: '请输入用户名称' }],
  password: [{ required: true, message: '请输入用户密码' }],
  accountCount: [{ required: true, message: '请输入账号额度' }],
  expireTime: [{ required: true, message: '请选择过期时间' }],
  websites: [{
    validator: (_rule: Rule, value: string[]) => {
      const invalid = (value || []).find((v) => !DOMAIN_PATTERN.test(v));
      return invalid
        ? Promise.reject(`域名格式不正确：${invalid}（勿带 http:// 或路径）`)
        : Promise.resolve();
    },
  }],
};

async function openModal(record?: SystemTenantApi.Tenant) {
  await loadPackageList();
  if (record?.id) {
    const detail = await getTenant(record.id);
    formData.value = {
      ...detail,
      // 后端返回毫秒时间戳 number，DatePicker value-format="x" 需要字符串
      expireTime: detail.expireTime != null ? String(detail.expireTime) : undefined,
    };
  } else {
    formData.value = { status: 0, accountCount: 0, websites: [] };
  }
  modalVisible.value = true;
}

async function handleModalOk() {
  try {
    await formRef.value?.validate();
  } catch {
    return;
  }
  modalLoading.value = true;
  try {
    const data = { ...formData.value } as SystemTenantApi.Tenant;
    if (data.id) {
      // 编辑时用户名/密码字段不提交
      delete data.username;
      delete data.password;
      await updateTenant(data);
    } else {
      await createTenant(data);
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
  <Card title="租户管理">
    <!-- 搜索表单 -->
    <Form layout="inline" class="mb-4" :model="searchForm">
      <FormItem label="租户名称">
        <Input v-model:value="searchForm.name" placeholder="请输入租户名称" allow-clear />
      </FormItem>
      <FormItem label="联系人">
        <Input v-model:value="searchForm.contactName" placeholder="请输入联系人" allow-clear />
      </FormItem>
      <FormItem label="联系手机">
        <Input v-model:value="searchForm.contactMobile" placeholder="请输入联系手机" allow-clear />
      </FormItem>
      <FormItem label="租户状态">
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
        <Button v-if="permissionStore.hasPermission('system:tenant:create')" type="primary" @click="openModal()">
          <PlusOutlined /> 新增租户
        </Button>
        <Button
          v-if="permissionStore.hasPermission('system:tenant:export')"
          :loading="exportLoading"
          @click="handleExport"
        >
          <DownloadOutlined /> 导出
        </Button>
        <Button
          v-if="permissionStore.hasPermission('system:tenant:delete')"
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
      :scroll="{ x: 1500 }"
      @change="(p: any) => { pagination.pageNo = p.current; pagination.pageSize = p.pageSize; loadData(); }"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'packageId'">
          {{ renderPackage(record.packageId) }}
        </template>
        <template v-if="column.key === 'status'">
          <DictTag type="common_status" :value="record.status" />
        </template>
        <template v-if="column.key === 'operation'">
          <Space>
            <Button v-if="permissionStore.hasPermission('system:tenant:update')" type="link" size="small" @click="openModal(record as SystemTenantApi.Tenant)">编辑</Button>
            <Button v-if="permissionStore.hasPermission('system:tenant:delete')" type="link" size="small" danger @click="handleDelete(record.id)">删除</Button>
          </Space>
        </template>
      </template>
    </Table>

    <!-- 新增/编辑 Modal -->
    <Modal v-model:open="modalVisible" :title="modalTitle" :confirm-loading="modalLoading" width="640px" @ok="handleModalOk" destroy-on-close>
      <Form
        ref="formRef"
        :model="formData"
        :rules="formRules"
        :label-col="{ span: 5 }"
        :wrapper-col="{ span: 18 }"
        class="mt-4"
      >
        <FormItem label="租户名称" name="name">
          <Input v-model:value="formData.name" placeholder="请输入租户名称" />
        </FormItem>
        <FormItem label="租户套餐" name="packageId">
          <Select v-model:value="formData.packageId" placeholder="请选择租户套餐" :options="packageOptions" />
        </FormItem>
        <FormItem label="联系人" name="contactName">
          <Input v-model:value="formData.contactName" placeholder="请输入联系人" />
        </FormItem>
        <FormItem label="联系手机" name="contactMobile">
          <Input v-model:value="formData.contactMobile" placeholder="请输入联系手机" />
        </FormItem>
        <template v-if="!formData.id">
          <FormItem label="用户名称" name="username">
            <Input v-model:value="formData.username" placeholder="请输入租户管理员的用户名称" />
          </FormItem>
          <FormItem label="用户密码" name="password">
            <Input.Password v-model:value="formData.password" placeholder="请输入租户管理员的用户密码" />
          </FormItem>
        </template>
        <FormItem v-else :wrapper-col="{ span: 18, offset: 5 }">
          <Alert
            type="info"
            show-icon
            message="如需修改该租户管理员的账号或密码，请先在顶栏切换到该租户，再前往「系统管理 → 用户管理」找到该管理员用户进行重置。"
          />
        </FormItem>
        <FormItem label="账号额度" name="accountCount">
          <InputNumber v-model:value="formData.accountCount" :min="0" placeholder="请输入账号额度" class="w-full" />
        </FormItem>
        <FormItem label="过期时间" name="expireTime">
          <DatePicker
            v-model:value="formData.expireTime"
            show-time
            format="YYYY-MM-DD HH:mm:ss"
            value-format="x"
            placeholder="请选择过期时间"
            class="w-full"
          />
        </FormItem>
        <FormItem label="绑定域名" name="websites">
          <Select
            v-model:value="formData.websites"
            mode="tags"
            :open="false"
            placeholder="填写域名后回车，勿带 http:// 或路径，如 a.example.com"
            allow-clear
          />
        </FormItem>
        <FormItem label="租户状态" name="status">
          <RadioGroup v-model:value="formData.status">
            <Radio :value="0">正常</Radio>
            <Radio :value="1">停用</Radio>
          </RadioGroup>
        </FormItem>
      </Form>
    </Modal>
  </Card>
</template>
