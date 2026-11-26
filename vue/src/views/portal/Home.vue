<script setup>
import { ArrowRight, ChatDotRound, Goods } from '@element-plus/icons-vue'
import { computed } from 'vue'
import { usePermissionStore } from '../../stores/permission'
import { useUserStore } from '../../stores/user'

const permission = usePermissionStore()
const user = useUserStore()
const inventory = computed(() => permission.pages.find((page) => page.component === 'portal/Inventory'))
</script>

<template>
  <section class="page">
    <div class="page-heading"><h1>前台工作台</h1></div>
    <section class="workspace-hero">
      <div class="workspace-hero-content">
        <h2>你好，{{ user.displayName }}</h2>
      </div>
    </section>
    <div class="portal-entries">
      <RouterLink v-if="permission.agentPath" :to="permission.agentPath" class="surface portal-entry">
        <span class="metric-icon"><ChatDotRound class="icon" aria-hidden="true" /></span>
        <span><strong>AI 工作台</strong><span>管理会话并回放执行步骤</span></span>
        <ArrowRight class="icon" aria-hidden="true" />
      </RouterLink>
      <RouterLink v-if="inventory" :to="inventory.path" class="surface portal-entry">
        <span class="metric-icon metric-blue"><Goods class="icon" aria-hidden="true" /></span>
        <span><strong>{{ inventory.menuName }}</strong><span>库存业务接口待接入</span></span>
        <ArrowRight class="icon" aria-hidden="true" />
      </RouterLink>
    </div>
  </section>
</template>
