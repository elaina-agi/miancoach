// 深浅主题:html.dark 控制 Element Plus 暗色变量
const KEY = 'miancoach-theme'

export function initTheme(): void {
  const saved = localStorage.getItem(KEY)
  const dark = saved ? saved === 'dark' : true // 默认深色
  apply(dark)
}

export function apply(dark: boolean): void {
  document.documentElement.classList.toggle('dark', dark)
  document.documentElement.setAttribute('data-theme', dark ? 'dark' : 'light')
}

export function isDark(): boolean {
  return document.documentElement.classList.contains('dark')
}

export function toggleTheme(): boolean {
  const next = !isDark()
  apply(next)
  localStorage.setItem(KEY, next ? 'dark' : 'light')
  return next
}
