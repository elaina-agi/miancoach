<template>
  <div class="src">
    <header class="src-head">
      <span class="src-title">来源原文</span>
      <div class="src-tabs" role="tablist" aria-label="切换来源">
        <button v-for="(r, i) in refs" :key="r.chunkId" role="tab" class="src-tab" :class="{ on: i === index }"
                :aria-selected="i === index" :title="r.question" @click="index = i">{{ i + 1 }}</button>
      </div>
      <button class="btn btn-ghost btn-icon btn-sm" aria-label="关闭" title="关闭（Esc）" @click="emit('close')">
        <X :size="16" />
      </button>
    </header>

    <div v-if="current" ref="scrollEl" class="src-scroll">
      <div class="src-tags">
        <span class="tag tag-accent">{{ groupLabel(current.group) }}</span>
        <span v-if="current.category" class="tag">{{ current.category }}</span>
      </div>
      <h2 class="src-q">{{ current.question }}</h2>
      <p class="src-doc">{{ current.title }}</p>

      <MarkdownView v-if="detail" :content="detail.content" class="src-md" />
      <div v-else-if="failed" class="src-fail">原文加载失败。<button class="link" @click="load">重试</button></div>
      <div v-else class="src-skel">
        <div class="skeleton" style="width: 92%" /><div class="skeleton" style="width: 100%" />
        <div class="skeleton" style="width: 78%" /><div class="skeleton" style="width: 86%" />
      </div>
    </div>

    <footer v-if="current" class="src-foot">
      <RouterLink :to="`/kb/${current.chunkId}`" class="btn btn-secondary btn-sm">
        <BookOpen :size="14" />在知识库中阅读
      </RouterLink>
      <a v-if="current.url" :href="current.url" target="_blank" rel="noopener noreferrer" class="btn btn-ghost btn-sm">
        原站文章<ExternalLink :size="13" />
      </a>
    </footer>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, ref, shallowRef, watch } from 'vue'
import { BookOpen, ExternalLink, X } from 'lucide-vue-next'
import MarkdownView from '../MarkdownView.vue'
import { corpusApi } from '../../api/client'
import type { AskRef, ChunkFull } from '../../api/types'
import { groupLabel } from '../../lib/catalog'

const props = defineProps<{ refs: AskRef[] }>()
const emit = defineEmits<{ close: [] }>()
const index = defineModel<number>('index', { required: true })

// 同一篇原文在一次对话里常被反复点开,按 chunkId 缓存,不重复请求
const cache = new Map<number, ChunkFull>()

const current = computed(() => props.refs[index.value] ?? null)
const detail = shallowRef<ChunkFull | null>(null)
const failed = ref(false)
const scrollEl = ref<HTMLElement | null>(null)

async function load() {
  const r = current.value
  if (!r) return
  failed.value = false
  detail.value = cache.get(r.chunkId) ?? null
  if (detail.value) return
  try {
    const d = await corpusApi.chunk(r.chunkId)
    cache.set(r.chunkId, d)
    if (current.value?.chunkId === r.chunkId) detail.value = d // 加载期间用户可能已切到别的来源
  } catch {
    if (current.value?.chunkId === r.chunkId) failed.value = true
  }
}

watch(() => current.value?.chunkId, () => {
  void load()
  void nextTick(() => scrollEl.value?.scrollTo({ top: 0 }))
}, { immediate: true })
</script>

<style scoped>
.src { height: 100%; display: flex; flex-direction: column; min-height: 0; background: var(--bg); }
.src-head { flex: none; display: flex; align-items: center; gap: 10px; height: 52px; padding: 0 10px 0 20px; border-bottom: 1px solid var(--line); }
.src-title { font-size: 13.5px; font-weight: 600; flex: none; }
.src-tabs { display: flex; gap: 4px; flex: 1; min-width: 0; overflow-x: auto; }
.src-tab { flex: none; width: 26px; height: 26px; border: 1px solid var(--line); border-radius: var(--r-sm); background: var(--surface); color: var(--ink-2); font-size: 12.5px; font-weight: 600; cursor: pointer; font-variant-numeric: tabular-nums; transition: all var(--t) var(--ease); }
.src-tab:hover { border-color: var(--line-strong); color: var(--ink); }
.src-tab.on { background: var(--ink); border-color: var(--ink); color: var(--bg); }

.src-scroll { flex: 1; min-height: 0; overflow-y: auto; padding: 22px 24px 32px; }
.src-tags { display: flex; gap: 6px; flex-wrap: wrap; }
.src-q { margin-top: 12px; font-size: 19px; line-height: 1.5; }
.src-doc { margin-top: 4px; font-size: 13px; color: var(--ink-3); }
.src-md { margin-top: 20px; padding-top: 18px; border-top: 1px solid var(--line); font-size: 14.5px; }
.src-skel { margin-top: 24px; display: flex; flex-direction: column; gap: 12px; }
.src-skel .skeleton { height: 14px; }
.src-fail { margin-top: 24px; color: var(--ink-2); font-size: 14px; }
.link { border: 0; background: none; padding: 0; color: var(--accent); cursor: pointer; font-weight: 500; }

.src-foot { flex: none; display: flex; gap: 8px; padding: 12px 20px calc(12px + env(safe-area-inset-bottom)); border-top: 1px solid var(--line); }
</style>
