<template>
  <div class="page-scroll">
    <div class="quiz">
      <!-- ── 开场:选范围和题量 ── -->
      <template v-if="stage === 'setup'">
        <header class="intro">
          <h1>模拟面试</h1>
          <p>系统随机抽题，你像面试时那样作答；AI 考官对照题库原文逐点批改，漏掉的点就是该补的。</p>
        </header>

        <section class="setup">
          <h2 class="sec">抽题范围</h2>
          <div class="scopes" role="radiogroup" aria-label="抽题范围">
            <button v-for="s in scopes" :key="s.value" role="radio" class="scope" :class="{ on: scope === s.value }"
                    :aria-checked="scope === s.value" @click="scope = s.value">
              <span class="scope-name">{{ s.label }}</span>
              <span class="scope-n">{{ s.count ? `${s.count} 题` : '' }}</span>
              <Check v-if="scope === s.value" :size="16" class="scope-check" />
            </button>
          </div>

          <h2 class="sec">题量</h2>
          <div class="seg" role="radiogroup" aria-label="题量">
            <button v-for="n in [3, 5, 10, 0]" :key="n" role="radio" :class="{ on: total === n }" :aria-checked="total === n"
                    @click="total = n">{{ n ? `${n} 题` : '不限' }}</button>
          </div>

          <div class="start-row">
            <button class="btn btn-primary btn-lg" :disabled="starting" @click="start">
              <LoaderCircle v-if="starting" :size="16" class="spin" />开始面试
            </button>
            <span class="hint">作答时按 <span class="kbd">Ctrl + Enter</span> 提交</span>
          </div>
          <p v-if="guest" class="guest-note">
            <Info :size="15" />
            <span>没登录也能练，只是成绩不会保存。<a href="#" @click.prevent="openLogin()">登录</a>后每一场都会记进复盘，方便回头查漏。</span>
          </p>
        </section>

        <ol class="steps">
          <li><b>抽题</b><span>从选定范围随机出题，同一场不重复</span></li>
          <li><b>作答</b><span>不用写得工整，像口头回答一样把要点说清楚</span></li>
          <li><b>批改</b><span>对照原文给分，列出答到的点和漏掉的点</span></li>
        </ol>
      </template>

      <!-- ── 作答 / 批改结果 ── -->
      <template v-else-if="stage === 'answer' || stage === 'result'">
        <div class="run-bar">
          <span class="progress-text num">{{ progressText }}</span>
          <div v-if="total" class="progress" role="progressbar" :aria-valuenow="doneCount" :aria-valuemax="total">
            <div class="progress-fill" :style="{ width: (doneCount / total) * 100 + '%' }" />
          </div>
          <span v-else class="progress-sp" />
          <button class="btn btn-ghost btn-sm" :disabled="grading" @click="endSession"><Flag :size="14" />结束面试</button>
        </div>

        <article v-if="current" class="question">
          <div class="q-tags">
            <span class="tag tag-accent">{{ groupLabel(current.q.group) }}</span>
            <span v-if="current.q.category" class="tag">{{ current.q.category }}</span>
          </div>
          <h2 class="qq">{{ current.q.question }}</h2>
        </article>

        <!-- 作答 -->
        <section v-if="stage === 'answer'" class="answer-box">
          <textarea ref="answerEl" v-model="answer" class="textarea answer" :disabled="grading"
                    placeholder="像面试时口头回答那样写下来：先给结论，再讲原理和关键细节…" @keydown="onAnswerKey" />
          <div class="answer-foot">
            <span class="count num">{{ answer.trim().length }} 字</span>
            <span class="hint"><span class="kbd">Ctrl + Enter</span> 提交</span>
            <span class="sp" />
            <button class="btn btn-secondary" :disabled="grading || picking" @click="skip">
              <LoaderCircle v-if="picking" :size="15" class="spin" /><Shuffle v-else :size="15" />换一题
            </button>
            <button class="btn btn-primary" :disabled="!answer.trim() || grading" @click="submit">
              <LoaderCircle v-if="grading" :size="15" class="spin" />提交作答
            </button>
          </div>
          <div v-if="grading" class="grading" role="status">
            <span class="dots"><i /><i /><i /></span>
            考官正在对照题库原文批改<span class="num">（{{ gradingSecs }} 秒）</span>
          </div>
        </section>

        <!-- 批改结果 -->
        <template v-if="stage === 'result' && current?.grade">
          <section class="result">
            <GradeResult :grade="current.grade" />
          </section>

          <div class="next-row">
            <button class="btn btn-primary btn-lg" :disabled="picking" @click="next">
              <LoaderCircle v-if="picking" :size="16" class="spin" />
              {{ isLast ? '查看本场总结' : '下一题' }}<ArrowRight v-if="!picking" :size="16" />
            </button>
            <span class="hint"><span class="kbd">Ctrl + Enter</span></span>
          </div>

          <details class="fold">
            <summary><ChevronRight :size="16" class="chev" />我的回答</summary>
            <p class="my-answer">{{ current.answer }}</p>
          </details>
          <details class="fold" open>
            <summary><ChevronRight :size="16" class="chev" />标准答案<span class="fold-sub">题库原文</span></summary>
            <MarkdownView v-if="std" :content="std.content" class="std" />
            <div v-else class="std-skel"><div class="skeleton" /><div class="skeleton" style="width: 80%" /></div>
          </details>
        </template>
      </template>

      <!-- ── 本场总结 ── -->
      <template v-else>
        <header class="intro">
          <h1>本场结束</h1>
          <p>{{ groupLabel(scope || null) }} · 共答 {{ doneCount }} 题</p>
        </header>

        <section class="summary">
          <div class="sum-score">
            <span class="sum-label">本场均分</span>
            <ScoreMeter v-if="avgScore !== null" :score="avgScore" />
            <p v-else class="sum-none">这一场没有评分（批改端是 mock 模式）。</p>
          </div>
          <ul class="sum-list">
            <li v-for="(r, i) in gradedRounds" :key="r.q.chunkId">
              <span class="sum-i num">{{ i + 1 }}</span>
              <RouterLink :to="`/kb/${r.q.chunkId}`" class="sum-q">{{ r.q.question }}</RouterLink>
              <ScoreBadge :score="r.grade?.score" />
            </li>
          </ul>
        </section>

        <section class="report">
          <div class="report-head">
            <h2><Sparkles :size="17" />AI 复盘</h2>
            <button v-if="!report" class="btn btn-secondary btn-sm" :disabled="reportLoading" @click="genReport">
              <LoaderCircle v-if="reportLoading" :size="14" class="spin" />{{ reportLoading ? `生成中（${reportSecs} 秒）` : '生成本场总结' }}
            </button>
          </div>
          <MarkdownView v-if="report" :content="report" />
          <p v-else class="report-hint">让 AI 汇总这一场的表现：哪些点掌握了、哪些是共性的漏洞、接下来先复习什么。</p>
        </section>

        <div class="end-row">
          <button class="btn btn-primary" @click="restart"><RotateCcw :size="15" />再来一场</button>
          <RouterLink v-if="sessionId" to="/records" class="btn btn-secondary"><ChartNoAxesColumn :size="15" />查看全部复盘</RouterLink>
          <button v-else-if="guest" class="btn btn-secondary" @click="openLogin()"><LogIn :size="15" />登录后保存成绩</button>
        </div>
        <p v-if="!sessionId" class="guest-note end-note">
          <Info :size="15" /><span>这一场是游客模式，没有保存，离开页面就没了。</span>
        </p>
      </template>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import { onBeforeRouteLeave, useRoute } from 'vue-router'
import {
  ArrowRight, ChartNoAxesColumn, Check, ChevronRight, Flag, Info, LoaderCircle, LogIn, RotateCcw, Shuffle, Sparkles,
} from 'lucide-vue-next'
import MarkdownView from '../components/MarkdownView.vue'
import GradeResult from '../components/quiz/GradeResult.vue'
import ScoreMeter from '../components/quiz/ScoreMeter.vue'
import ScoreBadge from '../components/quiz/ScoreBadge.vue'
import { corpusApi, quizApi } from '../api/client'
import type { ChunkFull, Grade, QuizQuestion } from '../api/types'
import { authReady, isLoggedIn, openLogin, watchUser } from '../lib/auth'
import { groupCounts, groupLabel, loadGroups, sortGroups } from '../lib/catalog'
import { confirm } from '../lib/confirm'
import { toast } from '../lib/toast'

type Stage = 'setup' | 'answer' | 'result' | 'summary'
interface Round { q: QuizQuestion; answer: string; grade: Grade | null }

const route = useRoute()

const stage = ref<Stage>('setup')
const scope = ref('')     // '' = 全题库
const total = ref(5)      // 0 = 不限
// 登录时是后端场次 id;游客为 '',抽题、批改、总结都走不落库的接口
const sessionId = ref('')
const guest = computed(() => authReady.value && !isLoggedIn.value)
const rounds = ref<Round[]>([])
/** 本场出过的题(含被「换一题」跳过的),再抽时排除 */
let shown: number[] = []

const answer = ref('')
const answerEl = ref<HTMLTextAreaElement | null>(null)
const starting = ref(false)
const picking = ref(false)
const grading = ref(false)
const std = shallowRef<ChunkFull | null>(null)
const report = ref<string | null>(null)
const reportLoading = ref(false)

const scopes = computed(() => {
  const counts = groupCounts.value
  const totalCount = Object.values(counts).reduce((a, b) => a + b, 0)
  return [
    { value: '', label: '全题库', count: totalCount },
    ...sortGroups(Object.keys(counts), (g) => g).map((g) => ({ value: g, label: groupLabel(g), count: counts[g] })),
  ]
})

const current = computed(() => rounds.value[rounds.value.length - 1] ?? null)
const gradedRounds = computed(() => rounds.value.filter((r) => r.grade))
const doneCount = computed(() => gradedRounds.value.length)
const isLast = computed(() => total.value > 0 && doneCount.value >= total.value)
const progressText = computed(() => {
  const n = stage.value === 'answer' ? doneCount.value + 1 : doneCount.value
  return total.value ? `第 ${n} / ${total.value} 题` : `第 ${n} 题`
})
const avgScore = computed(() => {
  const scored = gradedRounds.value.map((r) => r.grade?.score).filter((s): s is number => s != null)
  return scored.length ? Math.round(scored.reduce((a, b) => a + b, 0) / scored.length) : null
})

// 批改和生成总结都要等模型,显示已等待的秒数:慢的时候用户知道还在跑,不是卡死了
function useSeconds(active: () => boolean) {
  const secs = ref(0)
  let timer: number | undefined
  watch(active, (on) => {
    clearInterval(timer)
    secs.value = 0
    if (on) timer = window.setInterval(() => secs.value++, 1000)
  })
  onBeforeUnmount(() => clearInterval(timer))
  return secs
}
const gradingSecs = useSeconds(() => grading.value)
const reportSecs = useSeconds(() => reportLoading.value)

function pickScope(): string | null {
  return scope.value || null
}

/** 抽下一题。游客没有场次可查,本场出过的题全靠 shown 排除 */
function drawNext(): Promise<QuizQuestion> {
  return sessionId.value
    ? quizApi.pick(sessionId.value, pickScope(), shown)
    : quizApi.pickFree(pickScope(), shown)
}

function beginRound(q: QuizQuestion) {
  rounds.value.push({ q, answer: '', grade: null })
  shown.push(q.chunkId)
  answer.value = ''
  std.value = null
  stage.value = 'answer'
  void nextTick(() => {
    document.querySelector('.page-scroll')?.scrollTo({ top: 0 })
    answerEl.value?.focus({ preventScroll: true })
  })
}

async function start() {
  starting.value = true
  try {
    const q = await quizApi.start(pickScope())
    sessionId.value = q.sessionId ?? ''
    rounds.value = []
    shown = []
    report.value = null
    beginRound(q)
  } catch (e) {
    toast.error('开始失败：' + (e as Error).message)
  } finally {
    starting.value = false
  }
}

/** 换一题:当前题不作答、不计入本场,直接抽下一道(后端会跳过本场出过的题) */
async function skip() {
  picking.value = true
  try {
    const q = await drawNext()
    rounds.value.pop()
    beginRound(q)
  } catch (e) {
    toast.error('抽题失败：' + (e as Error).message)
  } finally {
    picking.value = false
  }
}

async function submit() {
  const r = current.value
  const text = answer.value.trim()
  if (!r || !text || grading.value) return
  grading.value = true
  try {
    const out = sessionId.value
      ? await quizApi.grade(sessionId.value, r.q.chunkId, text)
      : await quizApi.gradeFree(r.q.chunkId, text)
    r.answer = text
    r.grade = out.grade
    stage.value = 'result'
    void nextTick(() => document.querySelector('.page-scroll')?.scrollTo({ top: 0, behavior: 'smooth' }))
    corpusApi.chunk(r.q.chunkId).then((d) => { if (current.value === r) std.value = d }).catch(() => {})
  } catch (e) {
    toast.error('批改失败：' + (e as Error).message)
  } finally {
    grading.value = false
  }
}

async function next() {
  if (isLast.value) {
    stage.value = 'summary'
    return
  }
  picking.value = true
  try {
    beginRound(await drawNext())
  } catch (e) {
    toast.error('抽题失败：' + (e as Error).message)
  } finally {
    picking.value = false
  }
}

async function endSession() {
  if (stage.value === 'answer' && answer.value.trim()) {
    const ok = await confirm({ title: '结束这场面试？', message: '当前这题还没提交，写下的内容不会保存。', confirmText: '结束' })
    if (!ok) return
  }
  // 当前题没作答的话从本场去掉,总结里只算答过的
  if (current.value && !current.value.grade) rounds.value.pop()
  if (doneCount.value) stage.value = 'summary'
  else restart()
}

function restart() {
  stage.value = 'setup'
  sessionId.value = ''
  rounds.value = []
  shown = []
  answer.value = ''
  report.value = null
}

async function genReport() {
  reportLoading.value = true
  try {
    const out = sessionId.value
      ? await quizApi.report(sessionId.value)
      : await quizApi.reportFree(gradedRounds.value.map((r) => ({
        question: r.q.question,
        groupTag: r.q.group,
        score: r.grade?.score ?? null,
        hits: r.grade?.hits ?? [],
        missing: r.grade?.missing ?? [],
      })))
    report.value = out.report
  } catch (e) {
    toast.error('总结生成失败：' + (e as Error).message)
  } finally {
    reportLoading.value = false
  }
}

// ── 快捷键 ──
function onAnswerKey(e: KeyboardEvent) {
  if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) {
    e.preventDefault()
    void submit()
  }
}
function onWindowKey(e: KeyboardEvent) {
  if (stage.value === 'result' && e.key === 'Enter' && (e.ctrlKey || e.metaKey) && !picking.value) {
    e.preventDefault()
    void next()
  }
}

// 写了一半的答案,切走页面前确认一下(切走后整场状态都会丢)
onBeforeRouteLeave(async () => {
  if (grading.value || (stage.value === 'answer' && answer.value.trim())) {
    return confirm({ title: '离开模拟面试？', message: '这题还没提交，离开后写下的内容会丢失。', confirmText: '离开' })
  }
  return true
})

// 面试中途退出登录:已答的题留在账号里,剩下的按游客继续,不再往那个场次里写
watchUser((uid) => {
  if (!uid) sessionId.value = ''
})

onMounted(() => {
  void loadGroups()
  // 从复盘页「针对练习」跳过来:预选好那个板块
  const s = route.query.scope
  if (typeof s === 'string') scope.value = s
  window.addEventListener('keydown', onWindowKey)
})
onBeforeUnmount(() => window.removeEventListener('keydown', onWindowKey))
</script>

<style scoped>
.page-scroll { height: 100%; overflow-y: auto; }
.quiz { max-width: 760px; margin: 0 auto; padding: 40px 28px 64px; }

.intro h1 { font-size: 28px; letter-spacing: -0.02em; }
.intro p { margin-top: 8px; font-size: 15px; line-height: 1.7; color: var(--ink-2); text-wrap: pretty; }

.setup { margin-top: 28px; padding: 24px; border: 1px solid var(--line); border-radius: var(--r-xl); background: var(--surface); }
.sec { font-size: 13.5px; font-weight: 600; color: var(--ink-2); margin-bottom: 10px; }
.sec:not(:first-child) { margin-top: 24px; }
.scopes { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px; }
.scope { position: relative; display: flex; flex-direction: column; align-items: flex-start; gap: 2px; padding: 12px 14px; border: 1px solid var(--line); border-radius: var(--r-lg); background: transparent; cursor: pointer; text-align: left; transition: border-color var(--t) var(--ease), background var(--t) var(--ease); }
.scope:hover { border-color: var(--line-strong); background: var(--bg); }
.scope.on { border-color: var(--accent); background: var(--accent-soft); box-shadow: inset 0 0 0 1px var(--accent); }
.scope-name { font-size: 14.5px; font-weight: 600; }
.scope-n { font-size: 12.5px; color: var(--ink-3); font-variant-numeric: tabular-nums; }
.scope-check { position: absolute; top: 12px; right: 12px; color: var(--accent); }

.seg { display: inline-grid; grid-auto-flow: column; padding: 3px; border-radius: var(--r); background: var(--fill); }
.seg button { min-width: 68px; height: 32px; padding: 0 14px; border: 0; border-radius: 8px; background: transparent; color: var(--ink-2); font-weight: 500; cursor: pointer; transition: background var(--t) var(--ease), color var(--t) var(--ease); }
.seg button.on { background: var(--surface); color: var(--ink); font-weight: 600; box-shadow: 0 1px 2px rgba(0, 0, 0, 0.08); }
.start-row { margin-top: 28px; display: flex; align-items: center; gap: 16px; flex-wrap: wrap; }
.hint { font-size: 12.5px; color: var(--ink-3); display: inline-flex; align-items: center; gap: 5px; }
.guest-note { margin-top: 18px; display: flex; align-items: flex-start; gap: 8px; font-size: 13px; line-height: 1.7; color: var(--ink-3); }
.guest-note svg { flex: none; margin-top: 3px; }
.guest-note a { color: var(--accent); font-weight: 500; }
.guest-note a:hover { text-decoration: underline; }
.end-note { margin-top: 14px; }

.steps { margin: 28px 0 0; padding: 0; list-style: none; display: grid; grid-template-columns: repeat(3, 1fr); gap: 16px; counter-reset: step; }
.steps li { counter-increment: step; display: flex; flex-direction: column; gap: 4px; font-size: 13px; color: var(--ink-3); line-height: 1.6; }
.steps li::before { content: counter(step); width: 22px; height: 22px; display: grid; place-items: center; margin-bottom: 4px; border-radius: 50%; background: var(--fill); color: var(--ink-2); font-size: 12px; font-weight: 600; }
.steps b { color: var(--ink); font-weight: 600; font-size: 14px; }

/* 进行中 */
.run-bar { display: flex; align-items: center; gap: 14px; margin-bottom: 28px; }
.progress-text { font-size: 13.5px; font-weight: 600; flex: none; }
.progress { flex: 1; height: 6px; border-radius: 3px; background: var(--fill-strong); overflow: hidden; }
.progress-fill { height: 100%; border-radius: 0 3px 3px 0; background: var(--accent); transition: width 400ms var(--ease); }
.progress-sp { flex: 1; }

.q-tags { display: flex; gap: 6px; flex-wrap: wrap; }
.qq { margin-top: 12px; font-size: 24px; line-height: 1.5; letter-spacing: -0.015em; text-wrap: balance; }

.answer-box { margin-top: 24px; }
.answer { min-height: 240px; padding: 16px 18px; border-radius: var(--r-lg); font-size: 15px; line-height: 1.8; resize: vertical; }
.answer-foot { margin-top: 12px; display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.count { font-size: 12.5px; color: var(--ink-3); }
.sp { flex: 1; }
.grading { margin-top: 16px; display: flex; align-items: center; gap: 10px; padding: 14px 16px; border-radius: var(--r-lg); background: var(--accent-soft); color: var(--ink-2); font-size: 14px; }
.grading .dots { color: var(--accent); }

.result { margin-top: 28px; padding: 24px; border: 1px solid var(--line); border-radius: var(--r-xl); background: var(--surface); animation: fade-up 260ms var(--ease); }
.next-row { margin: 20px 0 8px; display: flex; align-items: center; gap: 12px; }

.fold { margin-top: 12px; border-top: 1px solid var(--line); }
.fold summary { display: flex; align-items: center; gap: 6px; padding: 14px 0; cursor: pointer; list-style: none; font-size: 15px; font-weight: 600; }
.fold summary::-webkit-details-marker { display: none; }
.fold .chev { color: var(--ink-3); transition: transform var(--t) var(--ease); }
.fold[open] .chev { transform: rotate(90deg); }
.fold-sub { font-size: 12.5px; font-weight: 400; color: var(--ink-3); margin-left: 4px; }
.my-answer { padding: 0 0 16px 22px; white-space: pre-wrap; font-size: 14.5px; line-height: 1.8; color: var(--ink-2); }
.std { padding: 0 0 20px 22px; }
.std-skel { padding: 0 0 20px 22px; display: flex; flex-direction: column; gap: 10px; }
.std-skel .skeleton { height: 14px; }

/* 总结 */
.summary { margin-top: 24px; padding: 24px; border: 1px solid var(--line); border-radius: var(--r-xl); background: var(--surface); }
.sum-label { display: block; font-size: 13px; color: var(--ink-3); margin-bottom: 10px; }
.sum-none { color: var(--ink-2); }
.sum-list { margin: 24px 0 0; padding: 0; list-style: none; border-top: 1px solid var(--line); }
.sum-list li { display: flex; align-items: center; gap: 12px; padding: 12px 0; border-bottom: 1px solid var(--line); }
.sum-list li:last-child { border-bottom: 0; padding-bottom: 0; }
.sum-i { width: 20px; flex: none; font-size: 13px; color: var(--ink-3); }
.sum-q { flex: 1; min-width: 0; font-size: 14.5px; line-height: 1.55; }
.sum-q:hover { color: var(--accent); }

.report { margin-top: 16px; padding: 20px 24px; border: 1px solid var(--line); border-radius: var(--r-xl); }
.report-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 10px; }
.report-head h2 { display: flex; align-items: center; gap: 8px; font-size: 16px; }
.report-head h2 svg { color: var(--accent); }
.report-hint { font-size: 14px; color: var(--ink-3); line-height: 1.7; }
.end-row { margin-top: 24px; display: flex; gap: 10px; flex-wrap: wrap; }

@media (max-width: 720px) {
  .quiz { padding: 22px 16px 40px; }
  .intro h1 { font-size: 22px; }
  .setup, .result, .summary { padding: 18px 16px; border-radius: var(--r-lg); }
  .report { padding: 16px; }
  .scopes { grid-template-columns: 1fr 1fr; }
  .seg { display: grid; grid-template-columns: repeat(4, 1fr); }
  .seg button { min-width: 0; padding: 0; }
  .steps { grid-template-columns: 1fr; gap: 12px; }
  .qq { font-size: 19px; }
  .answer { min-height: 200px; font-size: 16px; }
  .answer-foot .hint, .next-row .hint, .start-row .hint { display: none; }
  .answer-foot .btn { flex: 1; }
  .my-answer, .std, .std-skel { padding-left: 0; }
}
</style>
