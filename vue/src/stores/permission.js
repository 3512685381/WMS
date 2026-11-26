import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

function selectMenus(nodes, prefix) {
  if (!Array.isArray(nodes)) return []
  return nodes
    .filter((node) => {
      if (typeof node.path !== 'string' || !/^\/[a-zA-Z0-9/-]+$/.test(node.path)) return false
      if (node.path !== prefix && !node.path.startsWith(prefix + '/')) return false
      return node.menuType === 'C' || node.menuType === 'M'
    })
    .map((node) => ({ ...node, children: selectMenus(node.children, prefix) }))
    .filter((node) => node.menuType === 'C' || node.children.length > 0)
}

function collectPages(nodes) {
  return nodes.flatMap((node) => [
    ...(node.menuType === 'C' ? [node] : []),
    ...collectPages(node.children),
  ])
}

export const usePermissionStore = defineStore('permission', () => {
  const menus = ref([])
  const routesLoaded = ref(false)
  const mode = ref('')
  const pages = computed(() => collectPages(menus.value))
  const agentPath = computed(() => pages.value.find((menu) => menu.component === 'agent/AgentChat')?.path)
  const homePath = computed(() => {
    const preferred = mode.value === 'admin' ? '/admin/dashboard' : '/portal'
    return pages.value.find((menu) => menu.path === preferred)?.path || pages.value[0]?.path || '/404'
  })

  function setMenus(nodes, roleCode) {
    mode.value = ['ADMIN', 'OPERATOR'].includes(roleCode) ? 'admin' : 'portal'
    menus.value = selectMenus(nodes, '/' + mode.value)
  }

  function reset() {
    menus.value = []
    routesLoaded.value = false
    mode.value = ''
  }

  return { menus, routesLoaded, mode, pages, agentPath, homePath, setMenus, reset }
})
