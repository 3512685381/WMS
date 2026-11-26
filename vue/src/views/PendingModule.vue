<script setup>
import { Document, Link } from '@element-plus/icons-vue'
import { useRoute } from 'vue-router'
import { menuIcons } from '../components/menuIcons'
import { usePermissionStore } from '../stores/permission'

const route = useRoute()
const permission = usePermissionStore()
</script>

<template>
  <section class="page">
    <div class="page-heading">
      <h1>{{ route.meta.title }}</h1>
    </div>
    <div class="surface pending-module">
      <span class="empty-icon"><component :is="menuIcons[route.meta.icon] || Document" class="icon" aria-hidden="true" /></span>
      <h2>{{ route.meta.title }}待接入</h2>
      <p>Spring Boot 暂未提供此模块的业务接口。</p>
      <RouterLink v-if="route.path !== permission.homePath" class="link-button link-button-muted" :to="permission.homePath">
        <Link class="icon" aria-hidden="true" /> 返回工作台
      </RouterLink>
    </div>
  </section>
</template>
