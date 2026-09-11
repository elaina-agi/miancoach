<template>
  <div class="grade-panel">
    <div v-if="score != null" class="big-row">
      <ScoreRing :score="score" :size="92" />
      <div class="sum">
        <div class="sum-line ok">答对 {{ hits.length }} 个点</div>
        <div class="sum-line bad">漏掉 {{ missing.length }} 个点</div>
      </div>
    </div>
    <el-alert v-if="!score && score !== 0" type="warning" :closable="false" class="mnote">
      {{ comment || '未评分' }}
    </el-alert>
    <div v-else-if="comment" class="comment">{{ comment }}</div>

    <div v-if="hits.length" class="block">
      <div class="block-title ok"><span class="tag">✓</span> 答到的点</div>
      <ul class="list ok"><li v-for="(h, i) in hits" :key="i">{{ h }}</li></ul>
    </div>
    <div v-if="missing.length" class="block">
      <div class="block-title bad"><span class="tag">!</span> 漏掉的关键点</div>
      <ul class="list bad"><li v-for="(m, i) in missing" :key="i">{{ m }}</li></ul>
    </div>
  </div>
</template>

<script setup lang="ts">
import ScoreRing from './ScoreRing.vue'

defineProps<{
  score: number | null
  hits: string[]
  missing: string[]
  comment: string
}>()
</script>

<style scoped>
.grade-panel { display: flex; flex-direction: column; gap: 14px; }
.big-row { display: flex; align-items: center; gap: 20px; }
.sum { display: flex; flex-direction: column; gap: 6px; font-size: 13px; }
.sum-line { font-weight: 500; }
.sum-line.ok { color: var(--accent); }
.sum-line.bad { color: var(--warn); }
.comment { color: var(--text-dim); font-size: 13px; line-height: 1.6; }
.block { border: 1px solid var(--border); border-radius: var(--radius); padding: 10px 12px; }
.block-title { font-size: 12.5px; font-weight: 600; margin-bottom: 8px; display: flex; align-items: center; gap: 8px; }
.block-title .tag {
  width: 17px; height: 17px; border-radius: 50%; display: inline-grid; place-items: center;
  font-size: 11px; color: #fff;
}
.block-title.ok { color: var(--accent); }
.block-title.bad { color: var(--warn); }
.block-title.ok .tag { background: var(--accent-strong); }
.block-title.bad .tag { background: var(--warn); }
.list { margin: 0; padding-left: 18px; display: flex; flex-direction: column; gap: 5px; font-size: 13px; }
.list li::marker { }
.list.ok li::marker { color: var(--accent); }
.list.bad li::marker { color: var(--warn); }
.mnote { --el-alert-bg-color: var(--warn-soft); }
</style>
