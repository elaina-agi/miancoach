// 深浅主题。没手动选过就跟随系统(含系统中途切换);手动切过一次就记住,不再跟随。
// 首帧的主题由 index.html 的内联脚本设置,这里负责之后的切换。
import { ref } from 'vue'

type Theme = 'light' | 'dark'

const KEY = 'miancoach-theme'
const media = window.matchMedia('(prefers-color-scheme: dark)')

function saved(): Theme | null {
  const v = localStorage.getItem(KEY)
  return v === 'light' || v === 'dark' ? v : null
}

export const theme = ref<Theme>(saved() ?? (media.matches ? 'dark' : 'light'))

function apply(t: Theme): void {
  document.documentElement.dataset.theme = t
  // 手机浏览器地址栏的颜色跟着页面底色走
  document.querySelector('meta[name="theme-color"]')?.setAttribute('content', t === 'dark' ? '#161614' : '#f7f6f3')
}

export function initTheme(): void {
  apply(theme.value)
  media.addEventListener('change', (e) => {
    if (saved()) return
    theme.value = e.matches ? 'dark' : 'light'
    apply(theme.value)
  })
}

export function toggleTheme(): void {
  theme.value = theme.value === 'dark' ? 'light' : 'dark'
  localStorage.setItem(KEY, theme.value)
  apply(theme.value)
}
