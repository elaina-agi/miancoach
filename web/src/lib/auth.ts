// 登录状态与登录弹框。游客模式:不登录也能用大部分功能,
// 碰到需要账号的操作时调 requireLogin() 原地弹框,登录成功后调用方接着往下走。
import { computed, ref, watch } from 'vue'
import { authApi } from '../api/client'
import type { LoginOut } from '../api/types'
import { clearSession, currentUser, saveSession, token } from './session'

export { currentUser }

export const isLoggedIn = computed(() => currentUser.value !== null)

/** 启动时用 token 换用户信息的那一下还没完成。侧栏据此先不显示"登录"按钮,免得已登录用户看到它闪一下。 */
export const authReady = ref(false)

export async function initAuth(): Promise<void> {
  try {
    if (token.value) currentUser.value = await authApi.me()
  } catch {
    // token 失效(401)时 client 已经清掉了;后端没起来之类的错误不清 token,下次刷新再试
  } finally {
    authReady.value = true
  }
}

/**
 * 登录态确定后回调一次,之后每次登录、退出(含 token 失效被清掉)再回调。参数是当前用户 id,游客为 null。
 * 启动时 /me 还没回来的那段时间不回调:那时还不知道是不是游客,按游客处理会让已登录用户的历史闪一下空。
 * 在组件 setup 里调用,卸载时自动停止监听。
 */
export function watchUser(cb: (userId: string | null) => void): void {
  watch(() => (authReady.value ? currentUser.value?.id ?? null : undefined), (id) => {
    if (id !== undefined) cb(id)
  }, { immediate: true })
}

export function logout(): void {
  clearSession()
}

// ── 登录弹框 ──

export const dialogVisible = ref(false)
export const dialogTab = ref<'login' | 'register'>('login')
let pending: ((ok: boolean) => void) | null = null

/**
 * 需要登录才能继续的地方调用:已登录直接返回 true;未登录弹框,
 * 用户登录/注册成功 resolve(true),关掉弹框 resolve(false)。
 *
 *   if (!(await requireLogin())) return
 *   // 到这里一定已登录,接着执行原操作
 */
export function requireLogin(tab: 'login' | 'register' = 'login'): Promise<boolean> {
  if (isLoggedIn.value) return Promise.resolve(true)
  return openLogin(tab)
}

export function openLogin(tab: 'login' | 'register' = 'login'): Promise<boolean> {
  pending?.(false) // 上一次还没结束的等待直接作废
  dialogTab.value = tab
  dialogVisible.value = true
  return new Promise(resolve => { pending = resolve })
}

/** 弹框里登录/注册成功后调用 */
export function finishLogin(out: LoginOut): void {
  saveSession(out.token, out.user)
  dialogVisible.value = false
  pending?.(true)
  pending = null
}

/** 弹框关闭时调用。成功时 finishLogin 已经把 pending 清空,这里就是空操作 */
export function cancelLogin(): void {
  pending?.(false)
  pending = null
}
