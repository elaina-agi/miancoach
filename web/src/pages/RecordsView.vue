<template>
  <div class="page-scroll">
    <div class="records">
      <header class="intro">
        <h1>复盘</h1>
        <p>每次批改都会记在这里。先看哪一块最弱，再点进对应场次回看漏掉的点。</p>
      </header>

      <div v-if="loading" class="kpis">
        <div v-for="i in 4" :key="i" class="kpi"><div class="skeleton" style="width: 50%; height: 12px" /><div class="skeleton" style="width: 40%; height: 28px; margin-top: 12px" /></div>
      </div>

      <div v-else-if="guest" class="empty">
        <LogIn :size="26" />
        <h2>登录后查看复盘</h2>
        <p>模拟面试的成绩跟着账号保存。游客也能练，只是成绩不会记下来。</p>
        <div class="empty-actions">
          <button class="btn btn-primary" @click="openLogin()"><LogIn :size="15" />登录 / 注册</button>
          <RouterLink to="/quiz" class="btn btn-secondary"><NotebookPen :size="15" />先去练练</RouterLink>
        </div>
      </div>

      <div v-else-if="failed" class="empty">
        <CircleAlert :size="26" />
        <h2>复盘数据加载失败</h2>
        <p>确认后端已启动，再试一次。</p>
        <button class="btn btn-secondary" @click="load"><RotateCcw :size="15" />重试</button>
      </div>

      <div v-else-if="!sessions.length" class="empty">
        <ChartNoAxesColumn :size="26" />
        <h2>还没有面试记录</h2>
        <p>去模拟面试答几道题，这里会按板块统计出你的薄弱点。</p>
        <RouterLink to="/quiz" class="btn btn-primary"><NotebookPen :size="15" />开始模拟面试</RouterLink>
      </div>

      <template v-else>
        <!-- 总览 -->
        <section class="kpis" aria-label="总览">
          <div class="kpi"><span class="k">面试场次</span><span class="v">{{ overview?.sessions ?? sessions.length }}</span></div>
          <div class="kpi"><span class="k">作答题数</span><span class="v">{{ overview?.answered ?? '—' }}</span></div>
          <div class="kpi">
            <span class="k">平均分</span>
            <span class="v-row">
              <span class="v">{{ avg ?? '—' }}</span>
              <span v-if="avg != null" class="band" :class="scoreBand(avg)">{{ scoreLabel(avg) }}</span>
            </span>
          </div>
          <div class="kpi"><span class="k">累计漏掉的点</span><span class="v">{{ overview?.missed ?? '—' }}</span></div>
        </section>

        <div class="two">
          <!-- 薄弱点:均分最低的排在最前 -->
          <section class="panel">
            <div class="panel-head">
              <h2>薄弱点</h2>
              <span class="panel-sub">按板块 / 类目 · 均分从低到高</span>
            </div>
            <ul class="weak">
              <li v-for="w in shownWeak" :key="(w.groupTag ?? '') + '/' + (w.category ?? '')" class="w-row">
                <div class="w-top">
                  <span class="w-name">{{ groupLabel(w.groupTag) }}<template v-if="w.category"><span class="w-sep">/</span>{{ w.category }}</template></span>
                  <span class="w-meta num">{{ w.answered }} 题 · 漏 {{ w.missed }} 点</span>
                  <RouterLink v-if="w.groupTag" :to="{ path: '/quiz', query: { scope: w.groupTag } }" class="w-go" :title="`练${groupLabel(w.groupTag)}`">
                    去练<ArrowRight :size="13" />
                  </RouterLink>
                </div>
                <div v-if="w.avgScore != null" class="w-bar-row">
                  <div class="w-track"><div class="w-fill" :class="scoreBand(w.avgScore)" :style="{ width: Math.max(1, w.avgScore) + '%' }" /></div>
                  <span class="w-val num">{{ Math.round(w.avgScore) }}<small>{{ scoreLabel(w.avgScore) }}</small></span>
                </div>
                <div v-else class="w-none">未评分</div>
              </li>
            </ul>
            <button v-if="weakness.length > WEAK_LIMIT" class="btn btn-ghost btn-sm more" @click="weakAll = !weakAll">
              {{ weakAll ? '收起' : `展开全部 ${weakness.length} 项` }}<ChevronDown :size="14" :class="{ flip: weakAll }" />
            </button>
          </section>

          <section class="panel trend-panel">
            <div class="panel-head">
              <h2>最近场次得分</h2>
              <span class="panel-sub">每场均分，最多显示 20 场</span>
            </div>
            <ScoreTrend v-if="trend.length" :points="trend" @select="openById" />
            <p v-else class="panel-empty">还没有评过分的场次。</p>
          </section>
        </div>

        <!-- 全部场次:也是上面柱状图的表格版 -->
        <section class="panel">
          <div class="panel-head"><h2>面试记录</h2><span class="panel-sub">{{ sessions.length }} 场</span></div>
          <ul class="sess">
            <li v-for="s in sessions" :key="s.id">
              <button class="s-row" :class="{ on: active?.id === s.id }" @click="active = s">
                <span class="s-date">{{ friendlyDate(s.createdAt) }}</span>
                <span class="s-scope">{{ groupLabel(s.groupTag) }}</span>
                <span class="s-n num">{{ s.answered }} 题</span>
                <ScoreBadge :score="s.avgScore == null ? null : Math.round(s.avgScore)" />
                <ChevronRight :size="16" class="s-chev" />
              </button>
            </li>
          </ul>
        </section>
      </template>
    </div>

    <Sheet v-model:open="sheetOpen" side="right" :width="640" label="场次详情">
      <SessionDetail v-if="active" :session="active" @close="active = null" />
    </Sheet>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, shallowRef } from 'vue'
import {
  ArrowRight, ChartNoAxesColumn, ChevronDown, ChevronRight, CircleAlert, LogIn, NotebookPen, RotateCcw,
} from 'lucide-vue-next'
import Sheet from '../components/ui/Sheet.vue'
import ScoreBadge from '../components/quiz/ScoreBadge.vue'
import ScoreTrend, { type TrendPoint } from '../components/quiz/ScoreTrend.vue'
import SessionDetail from '../components/quiz/SessionDetail.vue'
import { quizApi } from '../api/client'
import type { QuizOverview, SessionRow, WeakRow } from '../api/types'
import { authReady, isLoggedIn, openLogin, watchUser } from '../lib/auth'
import { groupLabel } from '../lib/catalog'
import { friendlyDate, scoreBand, scoreLabel } from '../lib/format'

const WEAK_LIMIT = 6

const loading = ref(true)
const failed = ref(false)
const sessions = shallowRef<SessionRow[]>([])
const weakness = shallowRef<WeakRow[]>([])
const overview = shallowRef<QuizOverview | null>(null)
const weakAll = ref(false)
const active = shallowRef<SessionRow | null>(null)
const guest = computed(() => authReady.value && !isLoggedIn.value)

const sheetOpen = computed({
  get: () => active.value !== null,
  set: (v) => { if (!v) active.value = null },
})

const avg = computed(() => (overview.value?.avgScore == null ? null : Math.round(overview.value.avgScore)))

// 后端按漏点数排序;复盘时更想先看"答得最差的",这里改按均分升序,没评分的排最后
const sortedWeak = computed(() => [...weakness.value].sort((a, b) => {
  if (a.avgScore == null) return b.avgScore == null ? b.missed - a.missed : 1
  if (b.avgScore == null) return -1
  return a.avgScore - b.avgScore || b.missed - a.missed
}))
const shownWeak = computed(() => (weakAll.value ? sortedWeak.value : sortedWeak.value.slice(0, WEAK_LIMIT)))

/** 柱状图:时间正序,最近 20 场有分数的 */
const trend = computed<TrendPoint[]>(() => sessions.value
  .filter((s) => s.avgScore != null)
  .slice(0, 20)
  .reverse()
  .map((s) => ({ id: s.id, createdAt: s.createdAt, avg: Math.round(s.avgScore as number), answered: s.answered })))

function openById(id: string) {
  active.value = sessions.value.find((s) => s.id === id) ?? null
}

async function load() {
  loading.value = true
  failed.value = false
  try {
    const [s, w, o] = await Promise.all([quizApi.sessions(), quizApi.weakness(), quizApi.overview()])
    sessions.value = s
    weakness.value = w
    overview.value = o
  } catch {
    failed.value = true
  } finally {
    loading.value = false
  }
}

// 登录态确定后才加载(/me 没回来前不知道是谁);之后登录、退出都按新身份重来
watchUser((uid) => {
  active.value = null
  sessions.value = []
  weakness.value = []
  overview.value = null
  if (uid) void load()
  else loading.value = false
})
</script>

<style scoped>
.page-scroll { height: 100%; overflow-y: auto; }
.records { max-width: 1040px; margin: 0 auto; padding: 40px 28px 64px; display: flex; flex-direction: column; gap: 16px; }

.intro { margin-bottom: 12px; }
.intro h1 { font-size: 28px; letter-spacing: -0.02em; }
.intro p { margin-top: 8px; font-size: 15px; color: var(--ink-2); }

.empty { margin-top: 24px; padding: 56px 24px; display: flex; flex-direction: column; align-items: center; gap: 10px; text-align: center; border: 1px dashed var(--line-strong); border-radius: var(--r-xl); color: var(--ink-3); }
.empty h2 { margin-top: 4px; font-size: 17px; color: var(--ink); }
.empty p { font-size: 14px; margin-bottom: 8px; }
.empty-actions { display: flex; gap: 10px; flex-wrap: wrap; justify-content: center; }

.kpis { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; }
.kpi { display: flex; flex-direction: column; gap: 6px; padding: 18px 20px; border: 1px solid var(--line); border-radius: var(--r-lg); background: var(--surface); }
.k { font-size: 13px; color: var(--ink-3); }
.v-row { display: flex; align-items: center; gap: 10px; }
/* 统计值用比例数字:等宽数字在大字号下显得松 */
.v { font-size: 30px; font-weight: 650; line-height: 1.15; letter-spacing: -0.02em; }
.band { height: 22px; padding: 0 8px; display: inline-flex; align-items: center; border-radius: 999px; font-size: 12px; font-weight: 600; color: var(--ink); }
.band.good { background: var(--good-soft); }
.band.mid { background: var(--mid-soft); }
.band.bad { background: var(--bad-soft); }

.two { display: grid; grid-template-columns: 1.15fr 1fr; gap: 16px; }
/* 得分图那张卡片和左边薄弱点等高,图表撑满,右边不空出一截 */
.trend-panel { display: flex; flex-direction: column; }
.panel { padding: 20px 22px; border: 1px solid var(--line); border-radius: var(--r-lg); background: var(--surface); min-width: 0; }
.panel-head { display: flex; align-items: baseline; gap: 10px; margin-bottom: 16px; }
.panel-head h2 { font-size: 15.5px; }
.panel-sub { font-size: 12.5px; color: var(--ink-3); }
.panel-empty { font-size: 13.5px; color: var(--ink-3); padding: 24px 0; }

.weak { margin: 0; padding: 0; list-style: none; display: flex; flex-direction: column; gap: 16px; }
.w-top { display: flex; align-items: baseline; gap: 10px; }
.w-name { flex: 1; min-width: 0; font-size: 14px; font-weight: 500; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.w-sep { margin: 0 5px; color: var(--ink-3); font-weight: 400; }
.w-meta { flex: none; font-size: 12.5px; color: var(--ink-3); }
.w-go { flex: none; display: inline-flex; align-items: center; gap: 2px; font-size: 12.5px; font-weight: 500; color: var(--accent); }
.w-go:hover { text-decoration: underline; }
.w-bar-row { margin-top: 8px; display: flex; align-items: center; gap: 10px; }
/* 横条:8px 粗,同色系浅底轨;数据端圆角、基线方角 */
.w-track { flex: 1; height: 8px; border-radius: 4px; background: var(--fill); overflow: hidden; }
.w-fill { height: 100%; border-radius: 0 4px 4px 0; }
.w-fill.good { background: var(--good); }
.w-fill.mid { background: var(--mid); }
.w-fill.bad { background: var(--bad); }
.w-val { flex: none; min-width: 72px; display: inline-flex; align-items: baseline; gap: 5px; font-size: 14px; font-weight: 600; }
.w-val small { font-size: 12px; font-weight: 500; color: var(--ink-3); }
.w-none { margin-top: 6px; font-size: 12.5px; color: var(--ink-3); }
.more { margin: 14px 0 0 -8px; }
.more .flip { transform: rotate(180deg); }

.sess { margin: 0 -10px; padding: 0; list-style: none; }
.s-row { width: 100%; display: flex; align-items: center; gap: 14px; height: 48px; padding: 0 10px; border: 0; border-radius: var(--r); background: transparent; cursor: pointer; text-align: left; transition: background var(--t) var(--ease); }
.s-row:hover, .s-row.on { background: var(--fill); }
.s-date { width: 140px; flex: none; font-size: 14px; font-weight: 500; }
.s-scope { flex: 1; min-width: 0; font-size: 14px; color: var(--ink-2); }
.s-n { flex: none; width: 48px; text-align: right; font-size: 13px; color: var(--ink-3); }
.s-chev { flex: none; color: var(--ink-3); }

@media (max-width: 900px) {
  .two { grid-template-columns: 1fr; }
  .kpis { grid-template-columns: 1fr 1fr; }
}
@media (max-width: 720px) {
  .records { padding: 22px 16px 40px; }
  .intro h1 { font-size: 22px; }
  .intro p { font-size: 14px; }
  .kpi { padding: 14px 16px; }
  .v { font-size: 24px; }
  .panel { padding: 16px; }
  .s-date { width: auto; flex: 1; }
  .s-scope { flex: none; width: 64px; }
  .s-n { display: none; }
}
</style>
