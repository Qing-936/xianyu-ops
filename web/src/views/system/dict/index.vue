<script setup lang="ts">
import { ref, onMounted, computed } from 'vue';
import {
  Card, Table, Button, Space, Tag, Modal, message, Tabs, Form, Input,
  Select, InputNumber, RadioGroup, Radio,
} from 'ant-design-vue';
import { PlusOutlined, SearchOutlined, ReloadOutlined } from '@ant-design/icons-vue';
import { getDictTypePage, createDictType, updateDictType, deleteDictType, getDictType } from '#/api/system/dict/type';
import { getDictDataPage, createDictData, updateDictData, deleteDictData, getDictData } from '#/api/system/dict/data';
import type { SystemDictTypeApi } from '#/api/system/dict/type';
import type { SystemDictDataApi } from '#/api/system/dict/data';
import { formatDateTime } from '#/utils/format';

defineOptions({ name: 'SystemDict' });

const loading = ref(false);
const activeTab = ref('type');

// ============ 字典类型 ============
const typeData = ref<SystemDictTypeApi.DictType[]>([]);
const typeTotal = ref(0);
const typePagination = ref({ pageNo: 1, pageSize: 20 });
const typeSearch = ref({ name: '', type: '', status: undefined as number | undefined });

const typeColumns = [
  { title: '字典编号', dataIndex: 'id', width: 80 },
  { title: '字典名称', dataIndex: 'name', width: 200 },
  { title: '字典类型', dataIndex: 'type', width: 200 },
  { title: '状态', dataIndex: 'status', width: 100 },
  { title: '备注', dataIndex: 'remark', width: 200 },
  { title: '创建时间', dataIndex: 'createTime', width: 180, customRender: ({ text }: { text: string }) => formatDateTime(text) },
  { title: '操作', key: 'operation', width: 200 },
];

async function loadTypeData() {
  loading.value = true;
  try {
    const res = await getDictTypePage({ ...typePagination.value, ...typeSearch.value });
    typeData.value = res.list || [];
    typeTotal.value = res.total || 0;
  } finally {
    loading.value = false;
  }
}

function handleTypeSearch() { typePagination.value.pageNo = 1; loadTypeData(); }
function handleTypeReset() { typeSearch.value = { name: '', type: '', status: undefined }; handleTypeSearch(); }

function handleDeleteType(id: number) {
  Modal.confirm({ title: '确认删除', content: '确定要删除该字典类型吗？', async onOk() { await deleteDictType(id); message.success('删除成功'); loadTypeData(); } });
}

// ============ 字典数据 ============
const dataData = ref<SystemDictDataApi.DictData[]>([]);
const dataTotal = ref(0);
const dataPagination = ref({ pageNo: 1, pageSize: 20 });
const dataSearch = ref({ dictType: '', label: '', status: undefined as number | undefined });

const dataColumns = [
  { title: '字典编码', dataIndex: 'id', width: 80 },
  { title: '字典标签', dataIndex: 'label', width: 150 },
  { title: '字典键值', dataIndex: 'value', width: 150 },
  { title: '字典类型', dataIndex: 'dictType', width: 150 },
  { title: '排序', dataIndex: 'sort', width: 80 },
  { title: '状态', dataIndex: 'status', width: 100 },
  { title: '操作', key: 'operation', width: 200 },
];

async function loadDataData() {
  loading.value = true;
  try {
    const res = await getDictDataPage({ ...dataPagination.value, ...dataSearch.value });
    dataData.value = res.list || [];
    dataTotal.value = res.total || 0;
  } finally {
    loading.value = false;
  }
}

function handleDataSearch() { dataPagination.value.pageNo = 1; loadDataData(); }
function handleDataReset() { dataSearch.value = { dictType: '', label: '', status: undefined }; handleDataSearch(); }

function handleDeleteData(id: number) {
  Modal.confirm({ title: '确认删除', content: '确定要删除该字典数据吗？', async onOk() { await deleteDictData(id); message.success('删除成功'); loadDataData(); } });
}

onMounted(loadTypeData);

// ============ 字典类型 Modal ============
const typeModalVisible = ref(false);
const typeModalLoading = ref(false);
const typeModalTitle = computed(() => (typeFormData.value?.id ? '编辑字典类型' : '新增字典类型'));
const typeFormData = ref<Partial<SystemDictTypeApi.DictType>>({});
const FormItem = Form.Item;

function openTypeModal(record?: SystemDictTypeApi.DictType) {
  if (record?.id) {
    getDictType(record.id!).then((detail) => { typeFormData.value = { ...detail }; typeModalVisible.value = true; });
  } else {
    typeFormData.value = { status: 0 };
    typeModalVisible.value = true;
  }
}

async function handleTypeModalOk() {
  typeModalLoading.value = true;
  try {
    if (typeFormData.value?.id) {
      await updateDictType(typeFormData.value as SystemDictTypeApi.DictType);
    } else {
      await createDictType(typeFormData.value as SystemDictTypeApi.DictType);
    }
    message.success('操作成功');
    typeModalVisible.value = false;
    loadTypeData();
  } finally {
    typeModalLoading.value = false;
  }
}

// ============ 字典数据 Modal ============
const dataModalVisible = ref(false);
const dataModalLoading = ref(false);
const dataModalTitle = computed(() => (dataFormData.value?.id ? '编辑字典数据' : '新增字典数据'));
const dataFormData = ref<Partial<SystemDictDataApi.DictData>>({});

function openDataModal(record?: SystemDictDataApi.DictData) {
  if (record?.id) {
    getDictData(record.id!).then((detail) => { dataFormData.value = { ...detail }; dataModalVisible.value = true; });
  } else {
    dataFormData.value = { status: 0, sort: 0 };
    dataModalVisible.value = true;
  }
}

async function handleDataModalOk() {
  dataModalLoading.value = true;
  try {
    if (dataFormData.value?.id) {
      await updateDictData(dataFormData.value as SystemDictDataApi.DictData);
    } else {
      await createDictData(dataFormData.value as SystemDictDataApi.DictData);
    }
    message.success('操作成功');
    dataModalVisible.value = false;
    loadDataData();
  } finally {
    dataModalLoading.value = false;
  }
}
</script>

<template>
  <Card title="字典管理">
    <Tabs v-model:activeKey="activeTab" @change="(k: any) => String(k) === 'type' ? loadTypeData() : loadDataData()">
      <!-- ========== 字典类型 ========== -->
      <Tabs.TabPane key="type" tab="字典类型">
        <!-- 搜索 -->
        <Form layout="inline" class="mb-4" :model="typeSearch">
          <FormItem label="字典名称">
            <Input v-model:value="typeSearch.name" placeholder="请输入字典名称" allow-clear />
          </FormItem>
          <FormItem label="字典类型">
            <Input v-model:value="typeSearch.type" placeholder="请输入字典类型" allow-clear />
          </FormItem>
          <FormItem label="状态">
            <Select v-model:value="typeSearch.status" placeholder="请选择" allow-clear style="width: 120px">
              <Select.Option :value="0">正常</Select.Option>
              <Select.Option :value="1">停用</Select.Option>
            </Select>
          </FormItem>
          <FormItem>
            <Space>
              <Button type="primary" @click="handleTypeSearch"><SearchOutlined /> 搜索</Button>
              <Button @click="handleTypeReset"><ReloadOutlined /> 重置</Button>
            </Space>
          </FormItem>
        </Form>

        <div class="mb-4">
          <Button type="primary" @click="openTypeModal()"><PlusOutlined /> 新增类型</Button>
        </div>

        <Table :columns="typeColumns" :data-source="typeData" :loading="loading" :pagination="{ current: typePagination.pageNo, pageSize: typePagination.pageSize, total: typeTotal, showTotal: (t: number) => `共 ${t} 条` }" row-key="id" @change="(p: any) => { typePagination.pageNo = p.current; loadTypeData(); }">
          <template #bodyCell="{ column, record }">
            <template v-if="column.dataIndex === 'status'"><Tag :color="record.status === 0 ? 'green' : 'red'">{{ record.status === 0 ? '正常' : '停用' }}</Tag></template>
            <template v-if="column.key === 'operation'">
              <Space>
                <Button type="link" size="small" @click="openTypeModal(record as SystemDictTypeApi.DictType)">编辑</Button>
                <Button type="link" size="small" danger @click="handleDeleteType(record.id)">删除</Button>
              </Space>
            </template>
          </template>
        </Table>
      </Tabs.TabPane>

      <!-- ========== 字典数据 ========== -->
      <Tabs.TabPane key="data" tab="字典数据">
        <Form layout="inline" class="mb-4" :model="dataSearch">
          <FormItem label="字典类型">
            <Input v-model:value="dataSearch.dictType" placeholder="请输入字典类型" allow-clear />
          </FormItem>
          <FormItem label="字典标签">
            <Input v-model:value="dataSearch.label" placeholder="请输入字典标签" allow-clear />
          </FormItem>
          <FormItem label="状态">
            <Select v-model:value="dataSearch.status" placeholder="请选择" allow-clear style="width: 120px">
              <Select.Option :value="0">正常</Select.Option>
              <Select.Option :value="1">停用</Select.Option>
            </Select>
          </FormItem>
          <FormItem>
            <Space>
              <Button type="primary" @click="handleDataSearch"><SearchOutlined /> 搜索</Button>
              <Button @click="handleDataReset"><ReloadOutlined /> 重置</Button>
            </Space>
          </FormItem>
        </Form>

        <div class="mb-4">
          <Button type="primary" @click="openDataModal()"><PlusOutlined /> 新增数据</Button>
        </div>

        <Table :columns="dataColumns" :data-source="dataData" :loading="loading" :pagination="{ current: dataPagination.pageNo, pageSize: dataPagination.pageSize, total: dataTotal, showTotal: (t: number) => `共 ${t} 条` }" row-key="id" @change="(p: any) => { dataPagination.pageNo = p.current; loadDataData(); }">
          <template #bodyCell="{ column, record }">
            <template v-if="column.dataIndex === 'status'"><Tag :color="record.status === 0 ? 'green' : 'red'">{{ record.status === 0 ? '正常' : '停用' }}</Tag></template>
            <template v-if="column.key === 'operation'">
              <Space>
                <Button type="link" size="small" @click="openDataModal(record as SystemDictDataApi.DictData)">编辑</Button>
                <Button type="link" size="small" danger @click="handleDeleteData(record.id)">删除</Button>
              </Space>
            </template>
          </template>
        </Table>
      </Tabs.TabPane>
    </Tabs>

    <!-- 字典类型 Modal -->
    <Modal v-model:open="typeModalVisible" :title="typeModalTitle" :confirm-loading="typeModalLoading" width="600px" @ok="handleTypeModalOk" destroy-on-close>
      <Form :label-col="{ span: 5 }" :wrapper-col="{ span: 17 }" class="mt-4">
        <FormItem label="字典名称" required>
          <Input v-model:value="typeFormData.name" placeholder="请输入字典名称" />
        </FormItem>
        <FormItem label="字典类型" required>
          <Input v-model:value="typeFormData.type" placeholder="请输入字典类型" />
        </FormItem>
        <FormItem label="状态">
          <RadioGroup v-model:value="typeFormData.status">
            <Radio :value="0">正常</Radio>
            <Radio :value="1">停用</Radio>
          </RadioGroup>
        </FormItem>
        <FormItem label="备注">
          <Input.TextArea v-model:value="typeFormData.remark" placeholder="请输入备注" :rows="3" />
        </FormItem>
      </Form>
    </Modal>

    <!-- 字典数据 Modal -->
    <Modal v-model:open="dataModalVisible" :title="dataModalTitle" :confirm-loading="dataModalLoading" width="600px" @ok="handleDataModalOk" destroy-on-close>
      <Form :label-col="{ span: 5 }" :wrapper-col="{ span: 17 }" class="mt-4">
        <FormItem label="字典类型" required>
          <Input v-model:value="dataFormData.dictType" placeholder="请输入字典类型" />
        </FormItem>
        <FormItem label="字典标签" required>
          <Input v-model:value="dataFormData.label" placeholder="请输入字典标签" />
        </FormItem>
        <FormItem label="字典键值" required>
          <Input v-model:value="dataFormData.value" placeholder="请输入字典键值" />
        </FormItem>
        <FormItem label="排序">
          <InputNumber v-model:value="dataFormData.sort" :min="0" placeholder="请输入排序" class="w-full" />
        </FormItem>
        <FormItem label="状态">
          <RadioGroup v-model:value="dataFormData.status">
            <Radio :value="0">正常</Radio>
            <Radio :value="1">停用</Radio>
          </RadioGroup>
        </FormItem>
        <FormItem label="颜色类型">
          <Select v-model:value="dataFormData.colorType" placeholder="请选择颜色" allow-clear>
            <Select.Option value="default">默认</Select.Option>
            <Select.Option value="primary">主要</Select.Option>
            <Select.Option value="success">成功</Select.Option>
            <Select.Option value="warning">警告</Select.Option>
            <Select.Option value="danger">危险</Select.Option>
          </Select>
        </FormItem>
        <FormItem label="备注">
          <Input.TextArea v-model:value="dataFormData.remark" placeholder="请输入备注" :rows="3" />
        </FormItem>
      </Form>
    </Modal>
  </Card>
</template>