import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { login, logout } from '../api/auth'
import { readToken, writeToken } from '../api/request'

const roleLabels = {
  ADMIN: '管理员',
  OPERATOR: '仓库作业员',
  USER: '普通用户',
}

export const useUserStore = defineStore('user', () => {
  const token = ref(readToken())
  const account = ref(null)
  const displayName = computed(() => account.value?.nickname || account.value?.username || '')
  const roleLabel = computed(() => roleLabels[account.value?.roleCode] || '仓储用户')
  const initial = computed(() => (displayName.value.trim()[0] || 'W').toUpperCase())

  async function signIn(credentials) {
    const data = await login(credentials)
    writeToken(data.token)
    token.value = data.token
    account.value = data.user
  }

  function clear() {
    writeToken('')
    token.value = ''
    account.value = null
  }

  async function signOut() {
    const previousToken = token.value
    try {
      await logout()
    } catch (error) {
      if (error.status !== 401) throw error
    }
    if (token.value === previousToken) clear()
  }

  return { token, account, displayName, roleLabel, initial, signIn, signOut, clear }
})
