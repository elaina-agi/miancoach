// 确认框:await confirm({...}) 拿到 true/false,由全局唯一的 ConfirmHost 渲染。
import { shallowRef } from 'vue'

export interface ConfirmOptions {
  title: string
  message?: string
  confirmText?: string
  cancelText?: string
  /** 危险操作(删除等):确认按钮用红色 */
  danger?: boolean
}

export const confirmState = shallowRef<(ConfirmOptions & { resolve: (ok: boolean) => void }) | null>(null)

export function confirm(opts: ConfirmOptions): Promise<boolean> {
  confirmState.value?.resolve(false) // 上一个还没答的直接当取消
  return new Promise((resolve) => {
    confirmState.value = { ...opts, resolve }
  })
}

export function settleConfirm(ok: boolean): void {
  confirmState.value?.resolve(ok)
  confirmState.value = null
}
