<template>
  <aside class="history">
    <div class="hist-head">
      <span class="hist-title">历史会话</span>
      <button class="hist-new" title="开始新对话" :disabled="disabled" @click="emit('new')">
        <el-icon :size="14"><Plus /></el-icon>
      </button>
    </div>

    <p v-if="!sessions.length" class="hist-empty">
      还没有历史记录<br>问一个问题就会出现在这里
    </p>

    <div v-for="g in groups" :key="g.label" class="hist-group">
      <div class="hist-label">{{ g.label }}</div>
      <div v-for="s in g.items" :key="s.id" class="hist-item"
           :class="{ active: s.id === activeId }"
           :title="s.title"
           @click="!disabled && emit('select', s.id)">
        <span class="hist-item-q">{{ s.title }}</span>
        <span class="hist-item-time">{{ timeLabel(s.updatedAt, g.dated) }}</span>
        <button class="hist-del" title="删除这条会话" @click.stop="emit('remove', s)">
          <el-icon :size="12"><Delete /></el-icon>
        </button>
      </div>
    </div>
  </aside>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { Delete, Plus } from '@element-plus/icons-vue'
import type { ChatSessionRow } from '../api/types'

const props = defineProps<{
  sessions: ChatSessionRow[]
  activeId: string | null
  /** 生成中禁止切换/删除——切走的话流还在往旧会话里写,用户会看到错乱的内容 */
  disabled?: boolean
}>()

const emit = defineEmits<{
  select: [id: string]
  remove: [s: ChatSessionRow]
  new: []
}>()

/**
 * 按「今天 / 昨天 / 更早」分组。
 *
 * 边界用**本地时区**的零点算,不能直接拿 ISO 字符串切日期:
 * 后端给的是 UTC,而 UTC+8 的凌晨 0~8 点,UTC 日期比本地日期早一天,
 * 会把"刚刚问的"分到"昨天"。new Date(iso) 转成本地时间后再取年月日就没这个问题。
 *
 * 只做三档不按天铺开:用了三个月的话,每天一个标题会有近百个分组,
 * 大部分底下只有一条,列表比内容还长。越久越粗是会话列表的惯例。
 */
const groups = computed(() => {
  const now = new Date()
  const startOfToday = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime()
  const startOfYesterday = startOfToday - 24 * 60 * 60 * 1000

  const buckets = [
    { label: '今天', dated: false, items: [] as ChatSessionRow[] },
    { label: '昨天', dated: false, items: [] as ChatSessionRow[] },
    { label: '更早', dated: true, items: [] as ChatSessionRow[] },
  ]
  for (const s of props.sessions) {
    const t = new Date(s.updatedAt).getTime()
    if (t >= startOfToday) buckets[0].items.push(s)
    else if (t >= startOfYesterday) buckets[1].items.push(s)
    else buckets[2].items.push(s)
  }
  return buckets.filter(b => b.items.length > 0)
})

/** 今天/昨天只显示时刻;更早的显示月-日,否则全是 09:30 分不出是哪天。 */
function timeLabel(iso: string, dated: boolean): string {
  const d = new Date(iso)
  const p = (n: number) => String(n).padStart(2, '0')
  return dated
    ? `${p(d.getMonth() + 1)}-${p(d.getDate())}`
    : `${p(d.getHours())}:${p(d.getMinutes())}`
}
</script>

<style scoped>
.history {
  width: 214px; flex: none; display: flex; flex-direction: column;
  overflow-y: auto; border-right: 1px solid var(--border); padding-right: 14px;
}
.hist-head { display: flex; align-items: center; padding: 3px 4px 10px; }
.hist-title { font-size: 12px; font-weight: 600; color: var(--text-dim); }
.hist-new {
  margin-left: auto; border: 0; background: transparent; color: var(--text-dim);
  padding: 4px; border-radius: 7px; display: grid; place-items: center;
}
.hist-new:hover:not(:disabled) { background: var(--bg-hover); color: var(--accent); }
.hist-new:disabled { opacity: .4; }

.hist-empty { margin: 0; padding: 16px 4px; font-size: 12px; line-height: 1.8; color: var(--text-faint); }

.hist-group { margin-bottom: 8px; }
.hist-label { padding: 6px 4px 4px; font-size: 11px; color: var(--text-faint); }
.hist-item {
  display: flex; align-items: center; gap: 6px;
  padding: 7px 8px; border-radius: var(--radius-sm); cursor: pointer;
  transition: background var(--t-fast) var(--ease);
}
.hist-item:hover { background: var(--bg-hover); }
.hist-item.active { background: var(--accent-soft); }
.hist-item-q {
  flex: 1; min-width: 0; font-size: 12.5px; color: var(--text);
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.hist-item.active .hist-item-q { color: var(--accent); }
.hist-item-time { flex: none; font-size: 10.5px; color: var(--text-faint); }

/* 用 opacity 而不是 display:常驻占位,悬停时才显形,避免出现时把标题挤动 */
.hist-del {
  flex: none; opacity: 0; border: 0; background: transparent; color: var(--text-faint);
  padding: 2px; border-radius: 5px; display: grid; place-items: center;
  transition: opacity var(--t-fast) var(--ease);
}
.hist-item:hover .hist-del { opacity: 1; }
.hist-del:hover { color: var(--danger); background: color-mix(in srgb, var(--danger) 14%, transparent); }
</style>
