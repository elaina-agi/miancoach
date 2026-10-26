<template>
  <!-- 列表里的小分数:数字用墨色,档位靠左边的色点 + 悬停文字;没评分(mock)显示破折号 -->
  <span class="sb" :class="band" :title="score == null ? '未评分' : `${score} 分 · ${scoreLabel(score)}`">
    <i class="dot" />
    <span class="v">{{ score == null ? '—' : score }}</span>
  </span>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { scoreBand, scoreLabel } from '../../lib/format'

const props = defineProps<{ score?: number | null }>()
const band = computed(() => (props.score == null ? 'none' : scoreBand(props.score)))
</script>

<style scoped>
.sb { display: inline-flex; align-items: center; justify-content: flex-end; gap: 6px; min-width: 46px; height: 24px; padding: 0 9px; border-radius: 999px; background: var(--fill); font-size: 13px; font-weight: 600; color: var(--ink); font-variant-numeric: tabular-nums; flex: none; }
.dot { width: 7px; height: 7px; border-radius: 50%; background: var(--ink-3); }
.good { background: var(--good-soft); }
.good .dot { background: var(--good); }
.mid { background: var(--mid-soft); }
.mid .dot { background: var(--mid); }
.bad { background: var(--bad-soft); }
.bad .dot { background: var(--bad); }
.none .v { color: var(--ink-3); }
</style>
