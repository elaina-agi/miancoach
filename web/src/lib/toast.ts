// 轻提示。全局一个队列,ToastHost 负责渲染;任何地方 import { toast } 直接调。
import { reactive } from 'vue'

export interface ToastItem {
  id: number
  kind: 'success' | 'error' | 'info'
  text: string
}

export const toasts = reactive<ToastItem[]>([])
let seq = 0

function push(kind: ToastItem['kind'], text: string): void {
  const id = ++seq
  toasts.push({ id, kind, text })
  // 同时最多留 3 条,连续报错时不至于堆满屏幕
  if (toasts.length > 3) toasts.splice(0, toasts.length - 3)
  // 错误多留一会儿:用户往往要读完才知道下一步怎么办
  setTimeout(() => dismiss(id), kind === 'error' ? 4500 : 2600)
}

export function dismiss(id: number): void {
  const i = toasts.findIndex((t) => t.id === id)
  if (i >= 0) toasts.splice(i, 1)
}

export const toast = {
  success: (text: string) => push('success', text),
  error: (text: string) => push('error', text),
  info: (text: string) => push('info', text),
}
