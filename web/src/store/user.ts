import { defineStore } from 'pinia';

export interface UserInfo {
  id?: number;
  username?: string;
  nickname?: string;
  avatar?: string;
  deptId?: number;
  email?: string;
  mobile?: string;
  sex?: number;
  status?: number;
  homePath?: string;
}

export const useUserStore = defineStore('user', {
  state: (): {
    userInfo: UserInfo | null;
    roles: string[];
  } => ({
    userInfo: null,
    roles: [],
  }),

  getters: {
    isAdmin: (state) => state.roles.includes('super_admin'),
    nickname: (state) => state.userInfo?.nickname || state.userInfo?.username || '',
  },

  actions: {
    setUserInfo(info: UserInfo) {
      this.userInfo = info;
    },
    setRoles(roles: string[]) {
      this.roles = roles;
    },
    $reset() {
      this.userInfo = null;
      this.roles = [];
    },
  },

  persist: {
    key: 'xianyuops-user',
    pick: ['userInfo', 'roles'],
  },
});
