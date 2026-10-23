import { defineStore } from 'pinia';
import { getSimpleDictDataList } from '#/api/system/dict/data';
import type { SystemDictDataApi } from '#/api/system/dict/data';

export const useDictStore = defineStore('dict', {
  state: (): {
    dictMap: Record<string, SystemDictDataApi.DictData[]>;
    loaded: boolean;
  } => ({
    dictMap: {},
    loaded: false,
  }),

  getters: {
    /** 获取指定类型的字典数据 */
    getDictData: (state) => {
      return (dictType: string) => state.dictMap[dictType] || [];
    },
    /** 获取字典标签 */
    getDictLabel: (state) => {
      return (dictType: string, value: string) => {
        const list = state.dictMap[dictType] || [];
        const item = list.find((d) => d.value === value);
        return item?.label || '';
      };
    },
  },

  actions: {
    /** 从后端加载所有精简字典数据 */
    async loadDictData() {
      if (this.loaded) return;
      try {
        const data = await getSimpleDictDataList();
        const map: Record<string, SystemDictDataApi.DictData[]> = {};
        for (const item of data) {
          if (!map[item.dictType]) {
            map[item.dictType] = [];
          }
          map[item.dictType].push(item);
        }
        this.dictMap = map;
        this.loaded = true;
      } catch {
        // 字典加载失败不阻塞应用
      }
    },
    $reset() {
      this.dictMap = {};
      this.loaded = false;
    },
  },
});
