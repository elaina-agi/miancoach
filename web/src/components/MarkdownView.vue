<template>
  <div class="md" :class="{ citable }" @click="onClick" v-html="html" />
</template>

<script setup lang="ts">
import { computed } from 'vue'
import MarkdownIt from 'markdown-it'
import cjkFriendly from 'markdown-it-cjk-friendly'
import { toast } from '../lib/toast'

const props = defineProps<{
  content: string
  /** 回答里的 [n] 可点:点了打开第 n 篇来源。题库原文里的 [n] 只是普通上标 */
  citable?: boolean
  /** 流式生成中:在最后一个字后面画闪烁光标 */
  streaming?: boolean
}>()
const emit = defineEmits<{ cite: [n: number] }>()

const md = new MarkdownIt({
  html: false,       // 语料是 md,不允许原始 html
  linkify: true,
  breaks: true,      // 单换行也断行——LLM 回答常用单换行分隔要点,折叠成空格会糊成一坨
  typographer: false,
})
// CJK 加粗修复:**结论：**中文 这种"闭合**后紧贴文字"的写法,CommonMark 原生不解析
md.use(cjkFriendly)

// 外链新窗口打开
const defaultLink = md.renderer.rules.link_open
  || ((tokens, idx, options, _env, self) => self.renderToken(tokens, idx, options))
md.renderer.rules.link_open = (tokens, idx, options, env, self) => {
  tokens[idx].attrSet('target', '_blank')
  tokens[idx].attrSet('rel', 'noopener noreferrer')
  return defaultLink(tokens, idx, options, env, self)
}

// 代码块外面包一层,带语言名和复制按钮;复制靠根节点上的事件委托,v-html 里没法绑事件
const defaultFence = md.renderer.rules.fence!
md.renderer.rules.fence = (tokens, idx, options, env, self) => {
  const lang = md.utils.escapeHtml(tokens[idx].info.trim().split(/\s+/)[0] || '')
  return `<div class="code"><div class="code-bar"><span>${lang}</span><button type="button" class="code-copy">复制</button></div>`
    + defaultFence(tokens, idx, options, env, self) + '</div>'
}

// 引用标记 → 上标徽章。模型既会写 [1] 也会写合并的 [1, 3, 4] / [1、3],后者拆成一个个徽章;
// 代码块/行内代码里的 [n] 是字面内容,不转换
const CITE_RE = /\[(\d{1,2}(?:\s*[,，、]\s*\d{1,2})*)\]/g
const CODE_SEG_RE = /(<pre[\s\S]*?<\/pre>|<code[^>]*>[\s\S]*?<\/code>)/g
function decorateCites(html: string): string {
  return html
    .split(CODE_SEG_RE)
    .map((seg) => (seg.startsWith('<pre') || seg.startsWith('<code')
      ? seg
      // 前面加一个 WORD JOINER(U+2060):中文字后面本来随处可断行,不加的话角标会和前一个字分开、单独掉到下一行
      : seg.replace(CITE_RE, (_, list: string) => '&#8288;' + list.split(/\s*[,，、]\s*/)
        .map((n) => `<sup class="cite" data-cite="${n}">${n}</sup>`).join('&#8288;'))))
    .join('')
}

// 光标要跟在最后一个字后面(同一行),而不是另起一块:先在原文末尾放一个私用区占位符,
// 渲染成 html 后再换成光标元素。私用区字符不会出现在正常文本里
const CARET = ''
const html = computed(() => {
  const out = decorateCites(md.render((props.content ?? '') + (props.streaming ? CARET : '')))
  return props.streaming ? out.replace(CARET, '<span class="caret"></span>') : out
})

async function onClick(e: MouseEvent) {
  const t = e.target as HTMLElement
  const cite = t.closest<HTMLElement>('sup.cite')
  if (cite && props.citable) {
    emit('cite', Number(cite.dataset.cite))
    return
  }
  const copyBtn = t.closest<HTMLButtonElement>('.code-copy')
  if (copyBtn) {
    const code = copyBtn.closest('.code')?.querySelector('pre')?.textContent ?? ''
    try {
      await navigator.clipboard.writeText(code)
      copyBtn.textContent = '已复制'
      setTimeout(() => { copyBtn.textContent = '复制' }, 1500)
    } catch {
      toast.error('复制失败，浏览器没有给剪贴板权限')
    }
  }
}
</script>

<style scoped>
.md { font-size: 15px; line-height: 1.8; color: var(--ink); overflow-wrap: break-word; }
.md :deep(> :first-child) { margin-top: 0; }
.md :deep(> :last-child) { margin-bottom: 0; }

.md :deep(h1), .md :deep(h2), .md :deep(h3), .md :deep(h4) { font-weight: 650; line-height: 1.45; margin: 1.5em 0 0.6em; color: var(--ink); scroll-margin-top: 16px; }
.md :deep(h1) { font-size: 1.35em; }
.md :deep(h2) { font-size: 1.2em; }
.md :deep(h3) { font-size: 1.07em; }
.md :deep(h4) { font-size: 1em; color: var(--ink-2); }

.md :deep(p) { margin: 0.75em 0; text-wrap: pretty; }
.md :deep(ul), .md :deep(ol) { margin: 0.7em 0; padding-left: 1.4em; }
.md :deep(li) { margin: 0.3em 0; padding-left: 0.15em; }
.md :deep(li::marker) { color: var(--ink-3); }
.md :deep(ol > li::marker) { font-variant-numeric: tabular-nums; }
.md :deep(li > ul), .md :deep(li > ol) { margin: 0.25em 0; }
.md :deep(li > p) { margin: 0.3em 0; }

.md :deep(a) { color: var(--accent); text-decoration: underline; text-decoration-color: var(--accent-line); text-underline-offset: 3px; }
.md :deep(a:hover) { text-decoration-color: var(--accent); }
.md :deep(strong) { font-weight: 650; }

.md :deep(code) { font-family: var(--font-mono); font-size: 0.86em; padding: 0.12em 0.4em; border-radius: 5px; background: var(--code-bg); border: 1px solid var(--line); }
.md :deep(.code) { margin: 1em 0; border: 1px solid var(--line); border-radius: var(--r); background: var(--code-bg); overflow: hidden; }
.md :deep(.code-bar) { display: flex; align-items: center; justify-content: space-between; height: 32px; padding: 0 6px 0 12px; border-bottom: 1px solid var(--line); font-family: var(--font-mono); font-size: 11.5px; color: var(--ink-3); }
.md :deep(.code-copy) { height: 24px; padding: 0 8px; border: 0; border-radius: 5px; background: transparent; color: var(--ink-3); font-family: var(--font-sans); font-size: 12px; cursor: pointer; }
.md :deep(.code-copy:hover) { background: var(--fill-strong); color: var(--ink); }
.md :deep(pre) { margin: 0; padding: 12px 14px; overflow-x: auto; line-height: 1.65; }
.md :deep(pre code) { padding: 0; border: 0; background: transparent; font-size: 13px; }

.md :deep(blockquote) { margin: 1em 0; padding: 2px 0 2px 14px; border-left: 3px solid var(--line-strong); color: var(--ink-2); }
.md :deep(blockquote p) { margin: 0.4em 0; }

/* 表格横向放不下时整块滚动,不挤压列宽 */
.md :deep(table) { display: block; width: max-content; max-width: 100%; overflow-x: auto; border-collapse: collapse; margin: 1em 0; font-size: 13.5px; line-height: 1.6; }
.md :deep(th), .md :deep(td) { border: 1px solid var(--line); padding: 7px 12px; text-align: left; vertical-align: top; }
.md :deep(th) { background: var(--fill); font-weight: 600; white-space: nowrap; }

.md :deep(img) { max-width: 100%; border-radius: var(--r); }
.md :deep(hr) { border: 0; border-top: 1px solid var(--line); margin: 1.6em 0; }

.md :deep(.caret) { display: inline-block; width: 7px; height: 1.05em; margin-left: 2px; vertical-align: -0.17em; border-radius: 2px; background: var(--accent); animation: caret 1s steps(1) infinite; }
@keyframes caret { 50% { opacity: 0; } }

/* 引用徽章:[n] 变成小号上标,可点时像一个按钮 */
/* inline 而不是 inline-block:inline-block 会让后面的"。"被单独折到下一行(行首禁则对它不生效) */
.md :deep(sup.cite) { display: inline; margin: 0 1px; padding: 0.05em 0.42em; border-radius: 5px; background: var(--fill); color: var(--ink-2); font-size: 0.68em; font-weight: 650; line-height: 1.55; text-align: center; vertical-align: 0.5em; font-variant-numeric: tabular-nums; user-select: none; }
.md.citable :deep(sup.cite) { cursor: pointer; transition: background var(--t) var(--ease), color var(--t) var(--ease); }
.md.citable :deep(sup.cite:hover) { background: var(--accent-soft); color: var(--accent); }
</style>
