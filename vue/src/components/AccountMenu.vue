<script setup>
import { ArrowDown, SwitchButton } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '../stores/user'

const user = useUserStore()
const router = useRouter()
const details = ref(null)
const signingOut = ref(false)

function closeWhenBlurred(event) {
  if (!event.currentTarget.contains(event.relatedTarget)) details.value.open = false
}

async function doSignOut() {
  signingOut.value = true
  try {
    await user.signOut()
    await router.replace({ name: 'Login' })
  } catch (error) {
    ElMessage.error(error.message)
  } finally {
    signingOut.value = false
    if (details.value) details.value.open = false
  }
}
</script>

<template>
  <details ref="details" class="account-menu" @keydown.esc="details.open = false" @focusout="closeWhenBlurred">
    <summary class="account-trigger" aria-label="账户菜单">
      <span class="avatar">{{ user.initial }}</span>
      <span class="account-identity">
        <strong>{{ user.displayName }}</strong>
        <span>{{ user.roleLabel }}</span>
      </span>
      <ArrowDown class="icon account-arrow" aria-hidden="true" />
    </summary>
    <div class="account-panel">
      <span class="account-username">{{ user.account?.username }}</span>
      <button type="button" class="account-action" :disabled="signingOut" @click="doSignOut">
        <SwitchButton class="icon" aria-hidden="true" />
        {{ signingOut ? '正在退出…' : '退出登录' }}
      </button>
    </div>
  </details>
</template>
