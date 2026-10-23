<script setup lang="ts">
import { computed } from 'vue';
import { Tag } from 'ant-design-vue';
import { useDictStore } from '#/store/dict';

interface DictTagProps {
  /** 字典类型 */
  type: string;
  /** 字典值 */
  value?: any;
}

const props = defineProps<DictTagProps>();

const dictStore = useDictStore();

/** 获取字典标签及颜色 */
const dictInfo = computed(() => {
  if (!props.type || props.value === undefined || props.value === null) {
    return null;
  }

  const list = dictStore.getDictData(props.type);
  const item = list.find((d) => d.value === String(props.value));
  if (!item) {
    return null;
  }

  // 颜色映射：后端 colorType → Ant Design Vue Tag color
  let colorType = item.colorType || 'default';
  switch (colorType) {
    case 'danger': {
      colorType = 'error';
      break;
    }
    case 'info': {
      colorType = 'default';
      break;
    }
    case 'primary': {
      colorType = 'processing';
      break;
    }
    case 'warning': {
      colorType = 'warning';
      break;
    }
    case 'success': {
      colorType = 'success';
      break;
    }
    default: {
      break;
    }
  }

  // 如果 cssClass 是有效颜色值，直接使用
  const cssClass = item.cssClass;
  if (cssClass && /^#[0-9a-fA-F]{3,8}$/.test(cssClass)) {
    colorType = cssClass;
  }

  return {
    label: item.label || '',
    colorType,
  };
});
</script>

<template>
  <Tag v-if="dictInfo" :color="dictInfo.colorType">
    {{ dictInfo.label }}
  </Tag>
  <Tag v-else-if="value !== undefined && value !== null && value !== ''" color="default">
    {{ value }}
  </Tag>
  <span v-else>-</span>
</template>
