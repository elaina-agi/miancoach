<template>
  <div class="grade">
    <template v-if="grade.score != null">
      <ScoreMeter v-if="!compact" :score="grade.score" />
      <p v-if="grade.comment" class="comment">{{ grade.comment }}</p>
    </template>
    <!-- mock 模式没有分数,comment 里是"未真正批改"的说明 -->
    <div v-else class="unscored">
      <Info :size="16" />
      <p>{{ grade.comment || '这题没有评分' }}</p>
    </div>

    <div v-if="grade.hits.length || grade.missing.length" class="points">
      <section class="col">
        <h4><CircleCheck :size="16" class="ic good" />答到的点<span class="n">{{ grade.hits.length }}</span></h4>
        <ul v-if="grade.hits.length"><li v-for="(h, i) in grade.hits" :key="i">{{ h }}</li></ul>
        <p v-else class="none">这次没有命中得分点</p>
      </section>
      <section class="col">
        <h4><CircleAlert :size="16" class="ic bad" />漏掉的点<span class="n">{{ grade.missing.length }}</span></h4>
        <ul v-if="grade.missing.length"><li v-for="(m, i) in grade.missing" :key="i">{{ m }}</li></ul>
        <p v-else class="none">关键点都答到了</p>
      </section>
    </div>
  </div>
</template>

<script setup lang="ts">
import { CircleAlert, CircleCheck, Info } from 'lucide-vue-next'
import ScoreMeter from './ScoreMeter.vue'
import type { Grade } from '../../api/types'

defineProps<{
  grade: Grade
  /** 列表里展开时用:分数已经显示在标题行的徽标上,不再画大分数条 */
  compact?: boolean
}>()
</script>

<style scoped>
.grade { display: flex; flex-direction: column; gap: 18px; }
.comment { font-size: 15px; line-height: 1.75; color: var(--ink); }
.unscored { display: flex; gap: 10px; padding: 12px 14px; border-radius: var(--r); background: var(--mid-soft); font-size: 14px; line-height: 1.7; }
.unscored svg { flex: none; margin-top: 3px; color: var(--mid); }

/* 不拉成等高:一边只有"没有命中得分点"一句时,不留一大块空卡片 */
.points { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; align-items: start; }
.col { padding: 14px 16px; border-radius: var(--r-lg); background: var(--fill); }
.col h4 { display: flex; align-items: center; gap: 7px; margin: 0 0 10px; font-size: 14px; font-weight: 600; }
.ic.good { color: var(--good); }
.ic.bad { color: var(--bad); }
.n { margin-left: auto; font-size: 13px; font-weight: 500; color: var(--ink-3); font-variant-numeric: tabular-nums; }
.col ul { margin: 0; padding-left: 1.2em; display: flex; flex-direction: column; gap: 7px; font-size: 14px; line-height: 1.65; color: var(--ink-2); }
.col li::marker { color: var(--ink-3); }
.none { font-size: 13.5px; color: var(--ink-3); }

@media (max-width: 720px) {
  .points { grid-template-columns: 1fr; }
}
</style>
