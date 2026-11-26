<script setup>
import { ArrowRight, Close, Expand } from '@element-plus/icons-vue'
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import AccountMenu from '../components/AccountMenu.vue'
import BrandMark from '../components/BrandMark.vue'
import { usePermissionStore } from '../stores/permission'
import NavigationBranch from './NavigationBranch.vue'

const route = useRoute()
const permission = usePermissionStore()
const mobileOpen = ref(false)

const section = computed(() => permission.menus.find((menu) => (
  menu.menuType === 'M' && route.path.startsWith(menu.path + '/')
)))

watch(() => route.path, () => { mobileOpen.value = false })
</script>

<template>
  <div class="admin-shell" @keydown.esc="mobileOpen = false">
    <button v-if="mobileOpen" class="mobile-scrim" type="button" aria-label="关闭菜单" @click="mobileOpen = false"></button>
    <aside class="admin-sidebar" :class="{ 'is-open': mobileOpen }">
      <div class="sidebar-head">
        <RouterLink :to="permission.homePath" class="sidebar-brand" @click="mobileOpen = false">
          <BrandMark />
        </RouterLink>
        <button type="button" class="icon-button mobile-close" aria-label="关闭菜单" @click="mobileOpen = false">
          <Close class="icon" aria-hidden="true" />
        </button>
      </div>
      <nav class="admin-navigation" aria-label="后台菜单">
        <ul class="nav-list">
          <NavigationBranch v-for="menu in permission.menus" :key="menu.id" :node="menu" @navigate="mobileOpen = false" />
        </ul>
      </nav>
    </aside>

    <div class="admin-body">
      <header class="workspace-header">
        <button type="button" class="icon-button mobile-menu" aria-label="打开菜单" :aria-expanded="mobileOpen" @click="mobileOpen = true">
          <Expand class="icon" aria-hidden="true" />
        </button>
        <nav class="breadcrumbs" aria-label="面包屑">
          <RouterLink :to="permission.homePath">管理后台</RouterLink>
          <ArrowRight class="icon breadcrumb-arrow" aria-hidden="true" />
          <template v-if="section">
            <span>{{ section.menuName }}</span>
            <ArrowRight class="icon breadcrumb-arrow" aria-hidden="true" />
          </template>
          <strong aria-current="page">{{ route.meta.title }}</strong>
        </nav>
        <AccountMenu />
      </header>
      <main class="workspace-main" :class="{ 'workspace-main-chat': route.meta.module === 'agent/AgentChat' }">
        <RouterView />
      </main>
    </div>
  </div>
</template>
