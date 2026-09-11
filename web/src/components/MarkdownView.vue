<template>
  <div class="md-body" v-html="html" />
</template>

<script setup lang="ts">
import { computed } from 'vue'
import MarkdownIt from 'markdown-it'

const props = defineProps<{ content: string }>()

const md = new MarkdownIt({
  html: false,       // 语料是 md,不允许原始 html
  linkify: true,
  breaks: false,
  typographer: false,
})
// 外链新窗口打开
const defaultRender = md.renderer.rules.link_open
  || ((tokens, idx, options, _env, self) => self.renderToken(tokens, idx, options))
md.renderer.rules.link_open = (tokens, idx, options, env, self) => {
  tokens[idx].attrSet('target', '_blank')
  tokens[idx].attrSet('rel', 'noopener noreferrer')
  return defaultRender(tokens, idx, options, env, self)
}

const html = computed(() => md.render(props.content ?? ''))
</script>

<style scoped>
.md-body {
  font-size: 14px;
  line-height: 1.75;
  color: var(--text);
  overflow-wrap: break-word;
}
.md-body :deep(h1), .md-body :deep(h2), .md-body :deep(h3), .md-body :deep(h4) {
  font-weight: 600;
  line-height: 1.4;
  margin: 1.1em 0 0.5em;
  color: var(--text);
  scroll-margin-top: 12px;
}
.md-body :deep(h1) { font-size: 1.45em; }
.md-body :deep(h2) { font-size: 1.22em; padding-bottom: 0.28em; border-bottom: 1px solid var(--border); }
.md-body :deep(h3) { font-size: 1.08em; }
.md-body :deep(p) { margin: 0.55em 0; }
.md-body :deep(ul), .md-body :deep(ol) { margin: 0.5em 0; padding-left: 1.4em; }
.md-body :deep(li) { margin: 0.28em 0; }
.md-body :deep(a) { color: var(--info); text-decoration: none; }
.md-body :deep(a:hover) { text-decoration: underline; }
.md-body :deep(code) {
  font-family: var(--font-mono);
  font-size: 0.88em;
  background: var(--bg-soft);
  padding: 0.15em 0.4em;
  border-radius: 6px;
  color: var(--accent);
}
.md-body :deep(pre) {
  background: #0a0f1c;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  padding: 12px 14px;
  overflow-x: auto;
  margin: 0.7em 0;
}
.md-body :deep(pre code) {
  background: transparent;
  padding: 0;
  color: #c9d7e8;
  font-size: 12.5px;
  line-height: 1.6;
}
.md-body :deep(blockquote) {
  margin: 0.7em 0;
  padding: 0.1em 0 0.1em 0.9em;
  border-left: 3px solid var(--accent);
  background: var(--accent-soft);
  border-radius: 0 8px 8px 0;
  color: var(--text-dim);
}
.md-body :deep(table) {
  border-collapse: collapse;
  width: 100%;
  margin: 0.7em 0;
  font-size: 13px;
  display: block;
  overflow-x: auto;
}
.md-body :deep(th), .md-body :deep(td) {
  border: 1px solid var(--border);
  padding: 6px 10px;
  text-align: left;
}
.md-body :deep(th) { background: var(--bg-soft); font-weight: 600; }
.md-body :deep(tr:nth-child(2n)) { background: color-mix(in srgb, var(--bg-soft) 45%, transparent); }
.md-body :deep(img) { max-width: 100%; border-radius: var(--radius); margin: 0.4em 0; }
.md-body :deep(hr) { border: 0; border-top: 1px solid var(--border); margin: 1.1em 0; }
.md-body :deep(strong) { font-weight: 600; }
</style>
