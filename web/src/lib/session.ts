// 登录态的底层存储:token 存 localStorage(刷新后仍登录),user 只在内存里(启动时用 /me 换回来)。
// 单独成文件、不依赖任何模块:api/client 和 lib/auth 都要用它,放进 auth.ts 会形成循环引用。
import { ref } from 'vue'
import type { UserView } from '../api/types'

const KEY = 'miancoach-token'

export const token = ref<string | null>(localStorage.getItem(KEY))
export const currentUser = ref<UserView | null>(null)

export function saveSession(t: string, u: UserView): void {
  token.value = t
  currentUser.value = u
  localStorage.setItem(KEY, t)
}

export function clearSession(): void {
  token.value = null
  currentUser.value = null
  localStorage.removeItem(KEY)
}
