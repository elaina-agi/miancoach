// 弹框/侧滑面板/弹出层共用的行为:Esc 关闭、锁背景滚动、关闭后焦点还给打开它的元素。
//
// Esc 只关最上层:弹框里再开确认框时,按一次 Esc 只该关确认框。
// 所以维护一个栈,只有栈顶响应 Esc。
import { onBeforeUnmount, watch, type Ref } from 'vue'

const stack: Array<() => void> = []
let locks = 0

window.addEventListener('keydown', (e) => {
  if (e.key === 'Escape' && stack.length) {
    e.preventDefault()
    stack[stack.length - 1]()
  }
})

function lockScroll(on: boolean): void {
  locks += on ? 1 : -1
  document.body.style.overflow = locks > 0 ? 'hidden' : ''
}

/**
 * @param open   受控的打开状态
 * @param close  请求关闭(Esc 时调用)
 * @param modal  是否锁背景滚动;弹出菜单这种轻量浮层不锁
 */
export function useOverlay(open: Readonly<Ref<boolean>>, close: () => void, modal = true): void {
  let opener: HTMLElement | null = null
  const onEsc = () => close()

  const activate = () => {
    opener = document.activeElement as HTMLElement | null
    stack.push(onEsc)
    if (modal) lockScroll(true)
  }
  const deactivate = () => {
    const i = stack.lastIndexOf(onEsc)
    if (i < 0) return
    stack.splice(i, 1)
    if (modal) lockScroll(false)
    // 键盘用户关掉弹框后应回到原来的位置,而不是焦点丢到 body 上
    opener?.focus?.({ preventScroll: true })
    opener = null
  }

  watch(open, (v) => (v ? activate() : deactivate()), { immediate: true })
  onBeforeUnmount(deactivate)
}
