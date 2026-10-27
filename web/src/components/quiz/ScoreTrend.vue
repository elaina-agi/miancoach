<template>
  <!-- 最近各场均分的柱状图。单一序列:历史场次用弱化灰,最新一场用强调色并直接标数值,其余数值看悬停提示或下面的记录列表 -->
  <div class="trend">
    <div class="plot" @pointerleave="hover = -1">
      <div v-for="t in [100, 50, 0]" :key="t" class="grid" :style="{ bottom: t + '%' }"><span class="tick num">{{ t }}</span></div>
      <div class="cols">
        <button v-for="(p, i) in points" :key="p.id" class="col" :class="{ last: i === points.length - 1, on: hover === i }"
                :aria-label="`${friendlyDate(p.createdAt)}，均分 ${p.avg}，${p.answered} 题`"
                @pointerenter="hover = i" @focus="hover = i" @blur="hover = -1" @click="emit('select', p.id)">
          <span class="bar" :style="{ height: Math.max(1.5, p.avg) + '%' }">
            <span v-if="i === points.length - 1" class="cap num">{{ p.avg }}</span>
          </span>
        </button>
      </div>
      <div v-if="hover >= 0 && points[hover]" class="tip" :style="tipStyle">
        <strong class="num">{{ points[hover].avg }} 分</strong>
        <span>{{ friendlyDate(points[hover].createdAt) }} · {{ points[hover].answered }} 题</span>
      </div>
    </div>
    <div class="axis">
      <span>{{ points.length ? friendlyDate(points[0].createdAt, false) : '' }}</span>
      <span>{{ points.length > 1 ? friendlyDate(points[points.length - 1].createdAt, false) : '' }}</span>
    </div>
  </div>
</template>

<script lang="ts">
export interface TrendPoint { id: string; createdAt: string; avg: number; answered: number }
</script>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { friendlyDate } from '../../lib/format'

const props = defineProps<{ points: TrendPoint[] }>()
const emit = defineEmits<{ select: [id: string] }>()
const hover = ref(-1)

// 提示框跟着柱子走,靠右的柱子把提示框翻到左边,不出界
const tipStyle = computed(() => {
  const n = props.points.length
  const x = ((hover.value + 0.5) / n) * 100
  return x > 60 ? { right: `${100 - x}%`, transform: 'translateX(12px)' } : { left: `${x}%`, transform: 'translateX(-12px)' }
})
</script>

<style scoped>
/* 放在 flex 列里时撑满剩余高度(和旁边的薄弱点卡片等高),否则按最小高度画 */
.trend { flex: 1; min-height: 0; display: flex; flex-direction: column; gap: 8px; }
.plot { position: relative; flex: 1; min-height: 168px; max-height: 400px; margin-left: 30px; margin-top: 8px; }
/* 网格线:实线发丝线,比底色深一档,不抢数据 */
.grid { position: absolute; left: 0; right: 0; height: 0; border-top: 1px solid var(--line); }
.tick { position: absolute; right: calc(100% + 8px); top: -8px; font-size: 11px; color: var(--ink-3); }
.cols { position: absolute; inset: 0; display: flex; align-items: stretch; gap: 2px; }
.col { flex: 1; min-width: 0; display: flex; align-items: flex-end; justify-content: center; padding: 0; border: 0; background: transparent; cursor: pointer; border-radius: var(--r-sm); }
.col.on { background: var(--fill); }
/* 柱子最宽 24px,数据端 4px 圆角、基线端方角 */
.bar { position: relative; width: min(24px, 70%); border-radius: 4px 4px 0 0; background: color-mix(in srgb, var(--ink-3) 40%, transparent); transition: background var(--t) var(--ease); }
.col.on .bar { background: color-mix(in srgb, var(--ink-3) 65%, transparent); }
.col.last .bar { background: var(--accent); }
.cap { position: absolute; bottom: calc(100% + 4px); left: 50%; transform: translateX(-50%); font-size: 12px; font-weight: 600; color: var(--ink); }
.tip { position: absolute; top: 4px; z-index: 2; display: flex; flex-direction: column; gap: 2px; padding: 8px 10px; border-radius: var(--r); background: var(--surface); border: 1px solid var(--line); box-shadow: var(--shadow-pop); font-size: 12px; color: var(--ink-3); white-space: nowrap; pointer-events: none; }
.tip strong { font-size: 14px; color: var(--ink); font-weight: 650; }
.axis { margin-left: 30px; display: flex; justify-content: space-between; font-size: 11.5px; color: var(--ink-3); }
</style>
