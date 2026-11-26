<script setup>
import { ArrowRight, Lock, User } from '@element-plus/icons-vue'
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import BrandMark from '../../components/BrandMark.vue'
import { useUserStore } from '../../stores/user'

const user = useUserStore()
const route = useRoute()
const router = useRouter()
const credentials = reactive({ username: '', password: '' })
const busy = ref(false)
const errorMessage = ref('')

async function submit() {
  if (busy.value) return
  const username = credentials.username.trim()
  if (!username || !credentials.password) {
    errorMessage.value = '请输入用户名和密码。'
    return
  }

  busy.value = true
  errorMessage.value = ''
  try {
    await user.signIn({ username, password: credentials.password })
    await router.replace({ name: 'Entry', query: { redirect: route.query.redirect } })
  } catch (error) {
    errorMessage.value = error.message
  } finally {
    credentials.password = ''
    busy.value = false
  }
}
</script>

<template>
  <main class="login-layout">
    <section class="login-hero" aria-labelledby="brand-title">
      <BrandMark />
      <div class="login-hero-copy">
        <span class="hero-rule" aria-hidden="true"></span>
        <h1 id="brand-title">智能仓储<br />管理平台</h1>
        <p>管理仓储作业，查询库存与 AI 会话。</p>
      </div>
    </section>

    <section class="login-content" aria-labelledby="login-title">
      <div class="login-card">
        <h2 id="login-title">登录工作台</h2>
        <p>使用你的仓储系统账号继续。</p>

        <el-form class="login-form" label-position="top" size="large" @submit.prevent="submit">
          <el-form-item label="用户名">
            <el-input v-model="credentials.username" :prefix-icon="User" maxlength="64" placeholder="输入用户名" autocomplete="username" name="username" />
          </el-form-item>
          <el-form-item label="密码">
            <el-input v-model="credentials.password" :prefix-icon="Lock" type="password" show-password placeholder="输入密码" autocomplete="current-password" name="password" />
          </el-form-item>
          <p v-if="errorMessage" class="inline-error" role="alert">{{ errorMessage }}</p>
          <el-button class="login-submit" type="primary" native-type="submit" :loading="busy">
            <span>登录</span>
            <ArrowRight v-if="!busy" class="icon" aria-hidden="true" />
          </el-button>
        </el-form>
      </div>
    </section>
  </main>
</template>
