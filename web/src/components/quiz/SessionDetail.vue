<template>
  <div class="sd">
    <header class="sd-head">
      <div class="sd-title">
        <strong>{{ friendlyDate(session.createdAt) }}</strong>
        <span>{{ groupLabel(session.groupTag) }} · {{ session.answered }} 题</span>
      </div>
      <button class="btn btn-ghost btn-icon btn-sm" aria-label="关闭" @click="emit('close')"><X :size="16" /></button>
    </header>

    <div class="sd-scroll">
      <ScoreMeter v-if="session.avgScore != null" :score="Math.round(session.avgScore)" />

      <section class="sd-report">
        <div class="rh">
          <h3><Sparkles :size="16" />AI 总结</h3>
          <button v-if="!report" class="btn btn-secondary btn-sm" :disabled="reportLoading" @click="genReport">
            <LoaderCircle v-if="reportLoading" :size="14" class="spin" />{{ reportLoading ? '生成中…' : '生成' }}
          </button>
          <button v-else class="btn btn-ghost btn-sm" :disabled="reportLoading" @click="genReport">
            <LoaderCircle v-if="reportLoading" :size="14" class="spin" /><RotateCcw v-else :size="14" />重新生成
          </button>
        </div>
        <MarkdownView v-if="report" :content="report" class="report-md" />
        <p v-else class="rhint">汇总这一场哪些点掌握了、哪些是共性漏洞、接下来先复习什么。</p>
      </section>

      <h3 class="sd-sec">逐题回看</h3>
      <div v-if="loading" class="sd-skel"><div v-for="i in 3" :key="i" class="skeleton" /></div>
      <p v-else-if="!answers.length" class="rhint">这一场没有作答记录。</p>
      <details v-for="(a, i) in answers" :key="a.id" class="ans" :open="answers.length === 1">
        <summary>
          <span class="ai num">{{ i + 1 }}</span>
          <span class="aq">{{ a.question }}</span>
          <ScoreBadge :score="a.score" />
          <ChevronDown :size="16" class="chev" />
        </summary>
        <div class="ans-body">
          <GradeResult v-if="parsed(a)" :grade="parsed(a)!" compact />
          <div class="mine">
            <span class="mine-label">我的回答</span>
            <p>{{ a.userAnswer }}</p>
          </div>
          <RouterLink v-if="a.chunkId" :to="`/kb/${a.chunkId}`" class="btn btn-secondary btn-sm"><BookOpen :size="14" />看标准答案</RouterLink>
        </div>
      </details>
    </div>
  </div>
</template>

<script lang="ts">
// 总结要调模型、十几秒起步:同一次打开页面里生成过的就记住,来回切场次、关掉面板再打开都不用重新等。
// 放在模块作用域而不是 setup 里:面板关闭时组件会卸载,setup 里的缓存会跟着丢
const reportCache = new Map<string, string>()
</script>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { BookOpen, ChevronDown, LoaderCircle, RotateCcw, Sparkles, X } from 'lucide-vue-next'
import MarkdownView from '../MarkdownView.vue'
import GradeResult from './GradeResult.vue'
import ScoreBadge from './ScoreBadge.vue'
import ScoreMeter from './ScoreMeter.vue'
import { quizApi } from '../../api/client'
import type { AnswerRow, Grade, SessionRow } from '../../api/types'
import { groupLabel } from '../../lib/catalog'
import { friendlyDate } from '../../lib/format'
import { toast } from '../../lib/toast'

const props = defineProps<{ session: SessionRow }>()
const emit = defineEmits<{ close: [] }>()

const answers = ref<AnswerRow[]>([])
const loading = ref(true)
const report = ref<string | null>(null)
const reportLoading = ref(false)

function parsed(a: AnswerRow): Grade | null {
  if (!a.gradeJson) return null
  try {
    const g = JSON.parse(a.gradeJson) as Grade
    return { score: g.score ?? null, hits: g.hits ?? [], missing: g.missing ?? [], comment: g.comment ?? '' }
  } catch {
    return null
  }
}

watch(() => props.session.id, async (id) => {
  loading.value = true
  answers.value = []
  report.value = reportCache.get(id) ?? null
  try {
    const rows = await quizApi.answers(id)
    if (props.session.id === id) answers.value = rows
  } catch (e) {
    toast.error('记录加载失败：' + (e as Error).message)
  } finally {
    if (props.session.id === id) loading.value = false
  }
}, { immediate: true })

async function genReport() {
  const id = props.session.id
  reportLoading.value = true
  try {
    const r = (await quizApi.report(id)).report
    reportCache.set(id, r)
    if (props.session.id === id) report.value = r
  } catch (e) {
    toast.error('总结生成失败：' + (e as Error).message)
  } finally {
    reportLoading.value = false
  }
}
</script>

<style scoped>
.sd { height: 100%; display: flex; flex-direction: column; min-height: 0; }
.sd-head { flex: none; display: flex; align-items: center; gap: 10px; height: 60px; padding: 0 12px 0 24px; border-bottom: 1px solid var(--line); }
.sd-title { flex: 1; min-width: 0; display: flex; flex-direction: column; line-height: 1.35; }
.sd-title strong { font-size: 15px; font-weight: 600; }
.sd-title span { font-size: 12.5px; color: var(--ink-3); }
.sd-scroll { flex: 1; min-height: 0; overflow-y: auto; padding: 24px 24px 40px; }

.sd-report { margin-top: 24px; padding: 16px 18px; border: 1px solid var(--line); border-radius: var(--r-lg); background: var(--surface); }
.rh { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.rh h3 { display: flex; align-items: center; gap: 7px; font-size: 14.5px; }
.rh h3 svg { color: var(--accent); }
.report-md { margin-top: 10px; font-size: 14.5px; }
.rhint { margin-top: 6px; font-size: 13.5px; color: var(--ink-3); line-height: 1.7; }

.sd-sec { margin: 28px 0 8px; font-size: 14.5px; }
.sd-skel { display: flex; flex-direction: column; gap: 10px; }
.sd-skel .skeleton { height: 44px; border-radius: var(--r); }
.ans { border-top: 1px solid var(--line); }
.ans:last-of-type { border-bottom: 1px solid var(--line); }
.ans summary { display: flex; align-items: center; gap: 10px; padding: 13px 2px; cursor: pointer; list-style: none; }
.ans summary::-webkit-details-marker { display: none; }
.ans summary:hover .aq { color: var(--ink); }
.ai { width: 18px; flex: none; font-size: 12.5px; color: var(--ink-3); }
.aq { flex: 1; min-width: 0; font-size: 14.5px; line-height: 1.55; color: var(--ink-2); }
.ans[open] .aq { color: var(--ink); font-weight: 500; }
.chev { flex: none; color: var(--ink-3); transition: transform var(--t) var(--ease); }
.ans[open] .chev { transform: rotate(180deg); }
.ans-body { padding: 4px 0 20px 28px; display: flex; flex-direction: column; gap: 16px; align-items: flex-start; }
.ans-body > :first-child { align-self: stretch; }
.mine { align-self: stretch; }
.mine-label { font-size: 12.5px; font-weight: 600; color: var(--ink-3); }
.mine p { margin-top: 6px; white-space: pre-wrap; font-size: 14px; line-height: 1.75; color: var(--ink-2); }

@media (max-width: 720px) {
  .sd-head { padding-left: 16px; }
  .sd-scroll { padding: 18px 16px 32px; }
  .ans-body { padding-left: 0; }
}
</style>
