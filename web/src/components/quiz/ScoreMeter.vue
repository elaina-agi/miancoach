<template>
  <div class="score" :class="band">
    <div class="top">
      <span class="num-big">{{ score }}</span><span class="max">/ 100</span>
      <!-- 档位一定带图标 + 文字:颜色只是辅助,色弱用户也能读出好坏 -->
      <span class="band-label">
        <CircleCheck v-if="band === 'good'" :size="15" />
        <CircleDot v-else-if="band === 'mid'" :size="15" />
        <TriangleAlert v-else :size="15" />
        {{ scoreLabel(score) }}
      </span>
    </div>
    <div class="meter" role="meter" aria-valuemin="0" aria-valuemax="100" :aria-valuenow="score" :aria-label="`得分 ${score}`">
      <div class="fill" :style="{ width: Math.max(2, score) + '%' }" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { CircleCheck, CircleDot, TriangleAlert } from 'lucide-vue-next'
import { scoreBand, scoreLabel } from '../../lib/format'

const props = defineProps<{ score: number }>()
const band = computed(() => scoreBand(props.score))
</script>

<style scoped>
.score { display: flex; flex-direction: column; gap: 12px; }
.top { display: flex; align-items: baseline; gap: 6px; }
/* 大数字用比例数字(不用 tabular-nums),等宽数字在大字号下显得松 */
.num-big { font-size: 48px; font-weight: 650; line-height: 1; letter-spacing: -0.03em; }
.max { font-size: 15px; color: var(--ink-3); }
.band-label { margin-left: 10px; align-self: center; display: inline-flex; align-items: center; gap: 5px; height: 26px; padding: 0 10px; border-radius: 999px; font-size: 13px; font-weight: 600; color: var(--ink); }
.good .band-label { background: var(--good-soft); }
.mid .band-label { background: var(--mid-soft); }
.bad .band-label { background: var(--bad-soft); }
.good .band-label svg { color: var(--good); }
.mid .band-label svg { color: var(--mid); }
.bad .band-label svg { color: var(--bad); }

/* 仪表条:底轨是同色系的浅一档,填充色表示档位;填充端 4px 圆角、起点方角 */
.meter { height: 8px; border-radius: 4px; overflow: hidden; }
.fill { height: 100%; border-radius: 0 4px 4px 0; transition: width 600ms var(--ease); }
.good .meter { background: var(--good-soft); }
.mid .meter { background: var(--mid-soft); }
.bad .meter { background: var(--bad-soft); }
.good .fill { background: var(--good); }
.mid .fill { background: var(--mid); }
.bad .fill { background: var(--bad); }
</style>
