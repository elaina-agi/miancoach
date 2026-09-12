<template>
  <div class="md-body" v-html="html" />
</template>

<script setup lang="ts">
import { computed } from 'vue'
import MarkdownIt from 'markdown-it'
import cjkFriendly from 'markdown-it-cjk-friendly'

const props = defineProps<{ content: string }>()

const md = new MarkdownIt({
  html: false,       // 语料是 md,不允许原始 html
  linkify: true,
  breaks: true,      // 单换行也断行——LLM 回答常用单换行分隔要点,折叠成空格会糊成一坨
  typographer: false,
})
// CJK 加粗修复:**结论：**中文 这种"闭合**后紧贴文字"的写法,CommonMark 原生不解析
md.use(cjkFriendly)
// 外链新窗口打开
const defaultRender = md.renderer.rules.link_open
  || ((tokens, idx, options, _env, self) => self.renderToken(tokens, idx, options))
md.renderer.rules.link_open = (tokens, idx, options, env, self) => {
  tokens[idx].attrSet('target', '_blank')
  tokens[idx].attrSet('rel', 'noopener noreferrer')
  return defaultRender(tokens, idx, options, env, self)
}

// 引用标记 [1] → 上标徽章;代码块/行内代码里的 [n] 是字面内容,不转换
const CITE_RE = /\[(\d{1,2})\]/g
const CODE_SEG_RE = /(<pre[\s\S]*?<\/pre>|<code[^>]*>[\s\S]*?<\/code>)/g
function decorateCites(html: string): string {
  return html
    .split(CODE_SEG_RE)
    .map((seg) =>
      seg.startsWith('<pre') || seg.startsWith('<code')
        ? seg
        : seg.replace(CITE_RE, '<sup class="cite">$1</sup>'),
    )
    .join('')
}

const html = computed(() => decorateCites(md.render(props.content ?? '')))
</script>

<style scoped>
.md-body {
  font-size: 14px;
  line-height: 1.85;
  color: var(--text);
  overflow-wrap: break-word;
}
.md-body :deep(> :first-child) { margin-top: 0; }
.md-body :deep(> :last-child) { margin-bottom: 0; }

/* 标题层级:回答里多用 h2/h3 做小节,h4 兜底 */
.md-body :deep(h1), .md-body :deep(h2), .md-body :deep(h3), .md-body :deep(h4) {
  font-weight: 650;
  line-height: 1.4;
  margin: 1.25em 0 0.55em;
  color: var(--text);
  scroll-margin-top: 12px;
}
.md-body :deep(h1) { font-size: 1.4em; }
.md-body :deep(h2) {
  font-size: 1.18em;
  padding-left: 9px;
  border-left: 3px solid var(--accent);
  margin-top: 1.4em;
}
.md-body :deep(h3) {
  font-size: 1.06em;
  color: var(--text);
  padding-bottom: 0.2em;
  border-bottom: 1px dashed var(--border);
}
.md-body :deep(h4) { font-size: 1em; color: var(--text-dim); }

.md-body :deep(p) { margin: 0.6em 0; }

/* 列表:标记用主题色,条目间留呼吸感 */
.md-body :deep(ul), .md-body :deep(ol) { margin: 0.55em 0; padding-left: 1.5em; }
.md-body :deep(li) { margin: 0.35em 0; }
.md-body :deep(li::marker) { color: var(--accent); font-weight: 600; }
.md-body :deep(li > ul), .md-body :deep(li > ol) { margin: 0.3em 0; }

.md-body :deep(a) { color: var(--info); text-decoration: none; border-bottom: 1px dashed color-mix(in srgb, var(--info) 45%, transparent); }
.md-body :deep(a:hover) { color: var(--accent); border-bottom-color: var(--accent); }

.md-body :deep(code) {
  font-family: var(--font-mono);
  font-size: 0.87em;
  background: var(--bg-soft);
  padding: 0.14em 0.42em;
  border-radius: 6px;
  color: var(--accent);
  border: 1px solid color-mix(in srgb, var(--border) 60%, transparent);
}
.md-body :deep(pre) {
  background: #0a0f1c;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  padding: 12px 14px;
  overflow-x: auto;
  margin: 0.8em 0;
  line-height: 1.6;
}
.md-body :deep(pre code) {
  background: transparent;
  border: 0;
  padding: 0;
  color: #c9d7e8;
  font-size: 12.5px;
}

.md-body :deep(blockquote) {
  margin: 0.8em 0;
  padding: 0.35em 0.9em;
  border-left: 3px solid var(--accent);
  background: var(--accent-soft);
  border-radius: 0 8px 8px 0;
  color: var(--text-dim);
}
.md-body :deep(blockquote p) { margin: 0.3em 0; }

.md-body :deep(table) {
  border-collapse: collapse;
  width: 100%;
  margin: 0.8em 0;
  font-size: 13px;
  display: block;
  overflow-x: auto;
}
.md-body :deep(th), .md-body :deep(td) {
  border: 1px solid var(--border);
  padding: 6px 10px;
  text-align: left;
}
.md-body :deep(th) { background: var(--bg-soft); font-weight: 600; white-space: nowrap; }
.md-body :deep(tr:nth-child(2n)) { background: color-mix(in srgb, var(--bg-soft) 45%, transparent); }

.md-body :deep(img) { max-width: 100%; border-radius: var(--radius); margin: 0.4em 0; }
.md-body :deep(hr) { border: 0; border-top: 1px solid var(--border); margin: 1.2em 0; }
.md-body :deep(strong) { font-weight: 650; color: var(--text); }

/* 引用徽章:[n] 变成小号上标胶囊,和正文视觉分离 */
.md-body :deep(sup.cite) {
  font-family: var(--font-display);
  font-size: 0.68em;
  font-weight: 700;
  color: var(--accent);
  background: var(--accent-soft);
  border: 1px solid color-mix(in srgb, var(--accent) 35%, transparent);
  border-radius: 999px;
  padding: 0 0.45em;
  margin: 0 2px;
  line-height: 1.5;
  vertical-align: super;
  user-select: none;
  cursor: default;
}
</style>
