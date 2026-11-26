import { ref, watch } from 'vue'
import { createRouter, createWebHistory } from 'vue-router'
import { getMe, getMyMenus } from '../api/auth'
import { onUnauthorized } from '../api/request'
import { usePermissionStore } from '../stores/permission'
import { useUserStore } from '../stores/user'

const RouteStatus = () => import('../views/RouteStatus.vue')
const pages = import.meta.glob([
  '../views/admin/**/*.vue',
  '../views/portal/*.vue',
  '../views/agent/AgentChat.vue',
])
const pendingPage = () => import('../views/PendingModule.vue')

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'Entry', component: RouteStatus },
    { path: '/login', name: 'Login', component: () => import('../views/login/Login.vue') },
    { path: '/admin', name: 'AdminLayout', component: () => import('../layout/AdminLayout.vue') },
    { path: '/portal', name: 'PortalLayout', component: () => import('../layout/PortalLayout.vue') },
    { path: '/connection', name: 'Connection', component: RouteStatus },
    { path: '/404', name: 'Unavailable', component: RouteStatus },
    { path: '/:pathMatch(.*)*', name: 'NotFound', component: RouteStatus },
  ],
})

export const connectionError = ref('')

function menuRouteName(id) {
  return 'menu-' + id
}

function loginDestination(to) {
  return {
    name: 'Login',
    query: to.name === 'Entry' || to.name === 'Login' ? {} : { redirect: to.fullPath },
  }
}

function allowedDestination(requested, permission) {
  if (typeof requested === 'string' && requested.startsWith('/') && !requested.startsWith('//')) {
    const target = router.resolve(requested)
    if (permission.pages.some((page) => page.path === target.path)) return target.fullPath
  }
  return permission.homePath
}

export function installRouter(app, pinia) {
  const user = useUserStore(pinia)
  const permission = usePermissionStore(pinia)
  const removeRoutes = []
  let bootstrap

  watch(() => user.token, () => {
    for (const remove of removeRoutes.splice(0)) remove()
    permission.reset()
    bootstrap = undefined
    connectionError.value = ''
  }, { flush: 'sync' })

  async function loadRoutes(token) {
    const [account, menus] = await Promise.all([getMe(), getMyMenus()])
    if (user.token !== token) return

    user.account = account
    permission.setMenus(menus, account.roleCode)
    const layout = permission.mode === 'admin' ? 'AdminLayout' : 'PortalLayout'
    for (const page of permission.pages) {
      const component = pages['../views/' + page.component + '.vue'] || pendingPage
      const remove = router.addRoute(layout, {
        name: menuRouteName(page.id),
        path: page.path === '/portal' ? '' : page.path,
        component,
        meta: { title: page.menuName, icon: page.icon, module: page.component, permission: page.perms },
      })
      removeRoutes.push(remove)
    }
    permission.routesLoaded = true
  }

  function ensureRoutes() {
    if (permission.routesLoaded) return
    const token = user.token
    if (!bootstrap) {
      const pending = loadRoutes(token).finally(() => {
        if (bootstrap === pending) bootstrap = undefined
      })
      bootstrap = pending
    }
    return bootstrap
  }

  onUnauthorized((token) => {
    if (token !== user.token) return
    const current = router.currentRoute.value
    user.clear()
    if (current.name && current.name !== 'Login') void router.replace(loginDestination(current))
  })

  router.beforeEach(async (to) => {
    if (!user.token) {
      return to.name === 'Login' ? true : loginDestination(to)
    }

    try {
      await ensureRoutes()
    } catch (error) {
      if (!user.token || error.status === 401) return loginDestination(to)
      connectionError.value = error.message
      if (to.name === 'Connection') return true
      const requested = to.name === 'Login' ? to.query.redirect : to.fullPath
      return { name: 'Connection', query: { redirect: requested } }
    }

    if (!user.token) return loginDestination(to)
    if (to.name === 'AdminLayout') return permission.homePath
    if (['Entry', 'Login', 'Connection'].includes(to.name)) {
      return allowedDestination(to.query.redirect, permission)
    }

    const page = permission.pages.find((entry) => entry.path === to.path)
    if (page && to.name !== menuRouteName(page.id)) {
      return { name: menuRouteName(page.id), query: to.query, hash: to.hash, replace: true }
    }
    if (!page && (to.name === 'PortalLayout' || String(to.name).startsWith('menu-'))) {
      return { name: 'Unavailable', replace: true }
    }
    return true
  })

  router.afterEach((to, _from, failure) => {
    if (!failure) document.title = (to.meta.title || '智能仓储') + ' · WMS'
  })

  app.use(router)
}
