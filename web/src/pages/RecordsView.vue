<template>
  <div class="page">
    <header class="head">
      <div>
        <h1>复盘</h1>
        <p class="sub">批改记录会告诉你:哪一类题是你真正的薄弱区。</p>
      </div>
    </header>

    <div v-if="sessions.length === 0" class="card empty">
      <el-icon :size="40"><DataAnalysis /></el-icon>
      <p>还没有面试记录。去「模拟面试」答两题,这里就会长出你的薄弱雷达。</p>
      <el-button type="primary" @click="$router.push('/quiz')">去模拟面试</el-button>
    </div>

    <template v-else>
      <!-- 概览卡 -->
      <div class="cards">
        <div class="card kpi"><div class="k">面试次数</div><div class="v">{{ sessions.length }}</div></div>
        <div class="card kpi"><div class="k">平均得分</div><div class="v">{{ avgScore == null ? '—' : avgScore }}</div></div>
        <div class="card kpi"><div class="k">作答累计</div><div class="v">{{ totalAnswers }}</div></div>
        <div class="card kpi"><div class="k">累计漏点</div><div class="v warn">{{ totalMissed }}</div></div>
      </div>

      <div class="two">
        <!-- 会话列表 -->
        <aside class="sessions card">
          <div class="mini-title">面试场次</div>
          <div class="slist">
            <button v-for="s in sessions" :key="s.id" class="sess" :class="{ on: activeId === s.id }" @click="open(s)">
              <span class="sess-date">{{ fmt(s.createdAt) }}</span>
              <span class="sess-scope">{{ s.groupTag || '全题库' }}</span>
            </button>
          </div>
        </aside>

        <!-- 选中的场次明细 -->
        <section class="detail card">
          <div v-if="!active" class="ph">选一场面试查看逐题评分</div>
          <template v-else>
            <div class="mini-title">场次 {{ fmt(active.createdAt) }} · {{ active.groupTag || '全题库' }}</div>
            <div class="answers">
              <details v-for="a in answers" :key="a.id" class="ans-item">
                <summary>
                  <div class="aq">
                    <ScoreRing :score="a.score" :size="34" />
                    <div class="aq-body">
                      <span class="aq-q">{{ a.question }}</span>
                      <span class="aq-meta">{{ a.groupTag }} · {{ a.category || '' }} · {{ fmt(a.createdAt) }}</span>
                    </div>
                  </div>
                </summary>
                <div class="ans-body">
                  <div class="block"><div class="bt">我的作答</div>
                    <p class="pre">{{ a.userAnswer }}</p></div>
                  <GradePanel v-if="parsed(a)" :score="parsed(a)!.score" :hits="parsed(a)!.hits"
                              :missing="parsed(a)!.missing" :comment="parsed(a)!.comment" />
                  <div v-else class="ph small">此条没有批改数据</div>
                </div>
              </details>
            </div>
          </template>
        </section>
      </div>

      <!-- 薄弱聚合 -->
      <section class="card weak">
        <div class="mini-title">薄弱点 · 按板块/类目</div>
        <table class="weak-t">
          <thead><tr><th>范围</th><th>作答</th><th>均分</th><th>漏点</th></tr></thead>
          <tbody>
            <tr v-for="w in weakness" :key="w.groupTag + w.category">
              <td>{{ w.groupTag }}<span v-if="w.category" class="dim"> / {{ w.category }}</span></td>
              <td>{{ w.answered }}</td>
              <td>{{ w.avgScore ?? '—' }}</td>
              <td class="missed">{{ w.missed }}</td>
            </tr>
          </tbody>
        </table>
      </section>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import dayjs from 'dayjs'
import { quizApi } from '../api/client'
import type { AnswerRow, SessionRow, WeakRow, Grade } from '../api/types'
import ScoreRing from '../components/ScoreRing.vue'
import GradePanel from '../components/GradePanel.vue'

const sessions = ref<SessionRow[]>([])
const answers = ref<AnswerRow[]>([])
const weakness = ref<WeakRow[]>([])
const activeId = ref('')

const active = computed(() => sessions.value.find((s) => s.id === activeId.value))
const totalAnswers = computed(() => sessions.value.length)
const totalMissed = computed(() => weakness.value.reduce((s, w) => s + w.missed, 0))
const avgScore = computed(() => {
  const scored = answers.value.filter((a) => a.score != null)
  if (!scored.length) return null
  return Math.round(scored.reduce((s, a) => s + (a.score as number), 0) / scored.length)
})

function fmt(iso: string): string {
  return dayjs(iso).format('MM-DD HH:mm')
}

function parsed(a: AnswerRow): Grade | null {
  if (!a.gradeJson) return null
  try {
    return JSON.parse(a.gradeJson) as Grade
  } catch { return null }
}

async function open(s: SessionRow) {
  activeId.value = s.id
  try {
    answers.value = await quizApi.answers(s.id)
  } catch { answers.value = [] }
}

onMounted(async () => {
  try {
    sessions.value = await quizApi.sessions()
    weakness.value = await quizApi.weakness()
    if (sessions.value.length) await open(sessions.value[0])
  } catch { /* ignore */ }
})
</script>

<style scoped>
.page { display: flex; flex-direction: column; gap: 14px; max-width: 1000px; margin: 0 auto; }
.head h1 { margin: 0 0 4px; font-size: 24px; }
.sub { margin: 0; color: var(--text-dim); font-size: 13.5px; }
.card { border: 1px solid var(--border); border-radius: var(--radius); background: var(--bg-panel); padding: 16px 18px; box-shadow: var(--shadow-panel); }
.empty { text-align: center; color: var(--text-faint); padding: 40px 20px; }
.empty p { margin: 10px 0 14px; }

.cards { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; }
.kpi { display: flex; flex-direction: column; gap: 2px; }
.k { color: var(--text-dim); font-size: 12px; }
.v { font-size: 26px; font-weight: 700; font-family: var(--font-display); }
.v.warn { color: var(--warn); }

.two { display: grid; grid-template-columns: 240px 1fr; gap: 14px; }
.mini-title { font-weight: 600; font-size: 13px; margin-bottom: 10px; }
.slist { display: flex; flex-direction: column; gap: 4px; max-height: 420px; overflow-y: auto; }
.sess { display: flex; justify-content: space-between; border: 0; background: transparent; color: var(--text); padding: 8px 10px; border-radius: 8px; }
.sess:hover { background: var(--bg-hover); }
.sess.on { background: var(--accent-soft); }
.sess-date { font-family: var(--font-display); font-size: 12px; }
.sess-scope { color: var(--text-faint); font-size: 12px; }

.answers { display: flex; flex-direction: column; gap: 8px; }
.ans-item { border: 1px solid var(--border); border-radius: 10px; background: var(--bg-soft); }
.ans-item summary { list-style: none; cursor: pointer; padding: 10px 12px; }
.ans-item summary::-webkit-details-marker { display: none; }
.ans-item[open] summary { border-bottom: 1px solid var(--border); }
.aq { display: flex; align-items: center; gap: 12px; }
.aq-body { display: flex; flex-direction: column; min-width: 0; }
.aq-q { font-size: 13.5px; line-height: 1.4; }
.aq-meta { font-size: 11px; color: var(--text-faint); }
.ans-body { padding: 12px; display: flex; flex-direction: column; gap: 12px; }
.block .bt { font-size: 12px; color: var(--text-dim); margin-bottom: 6px; }
.pre { white-space: pre-wrap; font-size: 13px; color: var(--text-dim); margin: 0; }
.ph { color: var(--text-faint); text-align: center; padding: 30px 0; font-size: 13px; }
.ph.small { padding: 10px 0; }

.weak-t { width: 100%; border-collapse: collapse; font-size: 13px; }
.weak-t th, .weak-t td { text-align: left; padding: 7px 10px; border-bottom: 1px solid var(--border); }
.weak-t th { color: var(--text-dim); font-weight: 600; font-size: 12px; }
.weak-t .dim { color: var(--text-faint); }
.missed { color: var(--warn); font-weight: 600; }

@media (max-width: 860px) {
  .two { grid-template-columns: 1fr; }
  .cards { grid-template-columns: repeat(2, 1fr); }
}
</style>
