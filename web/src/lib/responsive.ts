import { onBeforeUnmount, ref, type Ref } from 'vue'

/**
 * 响应式断点监听。CSS 能用 @media 解决的优先用 CSS;
 * 这里只服务"模板结构随断点切换"的场景(如同一个组件一会儿内嵌、一会儿进抽屉),
 * 那种情况纯 CSS 挪不动 DOM,必须有 JS 侧的布尔值。
 */
export function useMediaQuery(query: string): Ref<boolean> {
  const mql = window.matchMedia(query)
  const matches = ref(mql.matches)
  const onChange = (e: MediaQueryListEvent) => { matches.value = e.matches }
  mql.addEventListener('change', onChange)
  onBeforeUnmount(() => mql.removeEventListener('change', onChange))
  return matches
}
