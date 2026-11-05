<template>
  <!-- 单道题的出处:读原文、看标准答案时跟在正文后面 -->
  <p v-if="url" class="credit">
    本题出自
    <a :href="url" target="_blank" rel="noopener noreferrer">{{ siteName }}<template v-if="title">《{{ title }}》</template></a>，版权归原作者所有。
  </p>
  <!-- 全站通用:题库来自哪里 -->
  <p v-else class="credit">
    题库来自
    <template v-for="(s, i) in CORPUS_SOURCES" :key="s.site">
      <template v-if="i > 0"> 与 </template>
      <a :href="s.url" target="_blank" rel="noopener noreferrer">{{ s.name }}</a>
    </template>，版权归原作者所有 · <RouterLink to="/about">关于本站</RouterLink>
  </p>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { CORPUS_SOURCES, sourceName } from '../lib/site'

/**
 * 题库来源标注。题库已取得原作者同意公开使用,条件是注明来源,所以凡是展示题库内容的地方都放一句。
 * 不传 url:全站通用的"题库来自 小林coding 与 小林面试笔记";传 url:这一道题出自哪篇原文。
 */
const props = defineProps<{ url?: string | null; title?: string | null }>()

const siteName = computed(() => sourceName(props.url) ?? '原站')
</script>

<style scoped>
.credit { font-size: 12.5px; line-height: 1.7; color: var(--ink-3); }
.credit a { color: var(--ink-2); text-decoration: underline; text-decoration-color: var(--line-strong); text-underline-offset: 3px; }
.credit a:hover { color: var(--accent); text-decoration-color: currentColor; }
</style>
