<template>
  <div class="history">
    <div class="h-top">
      <button class="btn btn-secondary new-btn" :disabled="disabled" @click="emit('new')">
        <Plus :size="16" />新对话
      </button>
    </div>

    <div class="h-scroll">
      <p v-if="loading && !sessions.length" class="h-empty">加载中…</p>
      <p v-else-if="!sessions.length" class="h-empty">还没有对话记录。<br>问的每个问题都会留在这里，方便回头复习。</p>

      <section v-for="g in groups" :key="g.label" class="h-group">
        <h3 class="h-label">{{ g.label }}</h3>
        <div v-for="s in g.items" :key="s.id" class="h-item" :class="{ on: s.id === activeId, off: disabled }"
             role="button" tabindex="0" :title="s.title" :aria-current="s.id === activeId ? 'page' : undefined"
             @click="!disabled && emit('select', s.id)" @keydown.enter="!disabled && emit('select', s.id)">
          <span class="h-title">{{ s.title }}</span>
          <button class="h-del" aria-label="删除这条对话" title="删除" :disabled="disabled" @click.stop="emit('remove', s)">
            <Trash2 :size="14" />
          </button>
        </div>
      </section>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { Plus, Trash2 } from 'lucide-vue-next'
import type { ChatSessionRow } from '../../api/types'
import { dayBucket } from '../../lib/format'

const props = defineProps<{
  sessions: ChatSessionRow[]
  activeId: string | null
  loading?: boolean
  /** 生成中禁止切换/删除——切走的话流还在往旧会话里写,用户会看到错乱的内容 */
  disabled?: boolean
}>()
const emit = defineEmits<{ select: [id: string]; remove: [s: ChatSessionRow]; new: [] }>()

// 后端已按最后活跃倒序,这里只按本地日期分桶,桶内保持原顺序
const groups = computed(() => {
  const order = ['今天', '昨天', '近 7 天', '更早'] as const
  const map = new Map<string, ChatSessionRow[]>()
  for (const s of props.sessions) {
    const b = dayBucket(s.updatedAt)
    map.set(b, [...(map.get(b) ?? []), s])
  }
  return order.filter((l) => map.has(l)).map((label) => ({ label, items: map.get(label)! }))
})
</script>

<style scoped>
.history { height: 100%; display: flex; flex-direction: column; min-height: 0; }
.h-top { padding: 12px 12px 8px; }
.new-btn { width: 100%; justify-content: flex-start; gap: 8px; }
.h-scroll { flex: 1; min-height: 0; overflow-y: auto; padding: 4px 8px 16px; }
.h-empty { padding: 16px 8px; font-size: 13px; line-height: 1.8; color: var(--ink-3); }
.h-group + .h-group { margin-top: 10px; }
.h-label { padding: 8px 8px 4px; font-size: 12px; font-weight: 500; color: var(--ink-3); }
.h-item { position: relative; display: flex; align-items: center; height: 36px; padding: 0 6px 0 10px; border-radius: var(--r); cursor: pointer; color: var(--ink-2); transition: background var(--t) var(--ease), color var(--t) var(--ease); }
.h-item:hover { background: var(--fill); color: var(--ink); }
.h-item.on { background: var(--fill-strong); color: var(--ink); font-weight: 500; }
.h-item.off { cursor: not-allowed; }
.h-title { flex: 1; min-width: 0; font-size: 13.5px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
/* 平时隐藏,悬停/聚焦/选中时出现;用 opacity 不用 display,出现时不挤动标题 */
.h-del { flex: none; width: 26px; height: 26px; display: grid; place-items: center; border: 0; border-radius: var(--r-sm); background: transparent; color: var(--ink-3); cursor: pointer; opacity: 0; transition: opacity var(--t) var(--ease), background var(--t) var(--ease), color var(--t) var(--ease); }
.h-item:hover .h-del, .h-item.on .h-del, .h-del:focus-visible { opacity: 1; }
.h-del:hover:not(:disabled) { background: var(--bad-soft); color: var(--bad); }
/* 触屏没有悬停,删除按钮常显 */
@media (hover: none) { .h-del { opacity: 1; } }
</style>
