<script setup>
import { Connection, Location } from '@element-plus/icons-vue'
import { useRoute } from 'vue-router'
import BrandMark from '../components/BrandMark.vue'
import { connectionError } from '../router'
import { usePermissionStore } from '../stores/permission'

const route = useRoute()
const permission = usePermissionStore()

function reload() {
  window.location.reload()
}
</script>

<template>
  <main class="route-state">
    <BrandMark />
    <section class="surface route-state-card">
      <template v-if="route.name === 'Connection'">
        <span class="empty-icon"><Connection class="icon" aria-hidden="true" /></span>
        <h1>暂时无法进入工作台</h1>
        <p>{{ connectionError || '未能加载账号或菜单，请重试。' }}</p>
        <el-button type="primary" @click="reload">重试</el-button>
      </template>
      <template v-else>
        <span class="empty-icon"><Location class="icon" aria-hidden="true" /></span>
        <h1>页面不可用</h1>
        <p>页面可能已移除，或当前账号没有访问权限。</p>
        <RouterLink v-if="permission.homePath !== '/404'" class="link-button" :to="permission.homePath">返回工作台</RouterLink>
      </template>
    </section>
  </main>
</template>
