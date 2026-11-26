<script setup>
import AccountMenu from '../components/AccountMenu.vue'
import BrandMark from '../components/BrandMark.vue'
import { usePermissionStore } from '../stores/permission'

const permission = usePermissionStore()
</script>

<template>
  <div class="portal-shell">
    <header class="portal-header">
      <div class="portal-header-inner">
        <RouterLink :to="permission.homePath"><BrandMark /></RouterLink>
        <nav class="portal-nav" aria-label="前台导航">
          <RouterLink v-for="menu in permission.pages" :key="menu.id" :to="menu.path" exact-active-class="portal-active">
            {{ menu.path === '/portal' ? '首页' : menu.menuName }}
          </RouterLink>
        </nav>
        <AccountMenu />
      </div>
    </header>
    <main class="portal-main" :class="{ 'workspace-main-chat': $route.meta.module === 'agent/AgentChat' }">
      <RouterView />
    </main>
  </div>
</template>
