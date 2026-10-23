<script setup lang="ts">
import { ref, computed } from 'vue';
import {
  Modal, Form, Input, InputNumber, RadioGroup, Switch, TreeSelect, message,
} from 'ant-design-vue';
import { getMenuList, createMenu, updateMenu } from '#/api/system/menu';
import type { SystemMenuApi } from '#/api/system/menu';

defineOptions({ name: 'MenuFormModal' });

const emit = defineEmits<{ success: [] }>();

const visible = ref(false);
const loading = ref(false);
const formData = ref<Partial<SystemMenuApi.Menu>>({});
const menuTreeSelectData = ref<any[]>([]);

const title = computed(() => (formData.value.id ? '编辑菜单' : '新增菜单'));
const FormItem = Form.Item;

/** 菜单类型选项：1 目录 2 菜单 3 按钮 */
const typeOptions = [
  { label: '目录', value: 1 },
  { label: '菜单', value: 2 },
  { label: '按钮', value: 3 },
];

/** 将扁平菜单列表构建为 TreeSelect 数据 */
function buildTreeSelectData(list: SystemMenuApi.Menu[]): any[] {
  const map = new Map<number, any>();
  const roots: any[] = [{ id: 0, name: '根目录', children: [] }];
  for (const item of list) {
    map.set(item.id, { id: item.id, name: item.name, children: [] });
  }
  for (const item of list) {
    const node = map.get(item.id)!;
    if (item.parentId && map.has(item.parentId)) {
      map.get(item.parentId)!.children.push(node);
    } else {
      roots[0].children.push(node);
    }
  }
  return roots;
}

/**
 * 打开弹窗
 * @param record 编辑时传入菜单记录
 * @param parentId 新增下级菜单时传入上级菜单 ID
 */
async function open(record?: Partial<SystemMenuApi.Menu>, parentId?: number) {
  // 加载菜单列表用于上级菜单选择
  const list = await getMenuList();
  menuTreeSelectData.value = buildTreeSelectData(list || []);

  if (record?.id) {
    formData.value = { ...record };
  } else {
    formData.value = {
      type: 2, sort: 0, status: 0, visible: true, keepAlive: true,
      parentId: parentId ?? 0,
    };
  }
  visible.value = true;
}

async function handleOk() {
  if (!formData.value.name) {
    message.warning('请输入菜单名称');
    return;
  }
  loading.value = true;
  try {
    if (formData.value.id) {
      await updateMenu(formData.value);
    } else {
      await createMenu(formData.value);
    }
    message.success('操作成功');
    visible.value = false;
    emit('success');
  } finally {
    loading.value = false;
  }
}

defineExpose({ open });
</script>

<template>
  <Modal
    v-model:open="visible"
    :title="title"
    :confirm-loading="loading"
    width="620px"
    destroy-on-close
    @ok="handleOk"
  >
    <Form :label-col="{ span: 5 }" :wrapper-col="{ span: 17 }" class="mt-4">
      <FormItem label="上级菜单">
        <TreeSelect
          v-model:value="formData.parentId"
          :tree-data="menuTreeSelectData"
          :field-names="{ label: 'name', value: 'id', children: 'children' }"
          placeholder="请选择上级菜单"
          tree-default-expand-all
          allow-clear
        />
      </FormItem>
      <FormItem label="菜单类型" required>
        <RadioGroup v-model:value="formData.type" button-style="solid" option-type="button" :options="typeOptions" />
      </FormItem>
      <FormItem label="菜单名称" required>
        <Input v-model:value="formData.name" placeholder="请输入菜单名称" />
      </FormItem>
      <FormItem label="排序">
        <InputNumber v-model:value="formData.sort" :min="0" placeholder="请输入排序" class="w-full" />
      </FormItem>
      <FormItem v-if="formData.type !== 3" label="路由地址">
        <Input v-model:value="formData.path" placeholder="如 system 或 user（按钮类型无需填写）" />
      </FormItem>
      <FormItem v-if="formData.type === 2" label="组件路径">
        <Input v-model:value="formData.component" placeholder="如 system/user/index" />
      </FormItem>
      <FormItem v-if="formData.type === 2" label="组件名称">
        <Input v-model:value="formData.componentName" placeholder="如 SystemUser" />
      </FormItem>
      <FormItem label="权限标识">
        <Input v-model:value="formData.permission" placeholder="如 system:user:list" />
      </FormItem>
      <FormItem v-if="formData.type !== 3" label="菜单图标">
        <Input v-model:value="formData.icon" placeholder="如 ant-design:user-outlined" />
      </FormItem>
      <FormItem v-if="formData.type !== 3" label="显示状态">
        <Switch v-model:checked="formData.visible" checked-children="显示" un-checked-children="隐藏" />
      </FormItem>
      <FormItem label="菜单状态">
        <RadioGroup v-model:value="formData.status" :options="[{ label: '正常', value: 0 }, { label: '停用', value: 1 }]" />
      </FormItem>
    </Form>
  </Modal>
</template>
