import { useUserStore } from '../stores/user'

/** 判断当前账号是否拥有某个权限编码（后端 sys_menu.perms）。 */
export function useCan() {
  const user = useUserStore()
  return (permission) => {
    const granted = user.account?.permissions
    return Array.isArray(granted) && granted.includes(permission)
  }
}
