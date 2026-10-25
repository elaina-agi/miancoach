<template>
  <div class="page">
    <header class="head">
      <div>
        <h1>模拟面试</h1>
        <p class="sub">系统抽题,你作答,以权威原文为准批改——漏点就是你该补的。</p>
      </div>
    </header>

    <!-- 开场:选范围 -->
    <section v-if="!sessionId" class="setup card">
      <p class="set-label">抽题范围</p>
      <div class="scope-row">
        <button v-for="s in scopes" :key="s.value" class="scope" :class="{ on: scope === s.value }" @click="scope = s.value">
          {{ s.label }}
        </button>
      </div>
      <div class="start-row">
        <el-button type="primary" size="large" :loading="starting" @click="start">
          开始面试
        </el-button>
      </div>
    </section>

    <!-- 面试进行 -->
    <template v-else>
      <section v-if="question" class="card qcard">
        <div class="qmeta">
          <span class="tag accent">{{ question.group }}</span>
          <span v-if="question.category" class="tag">{{ question.category }}</span>
          <span class="tag dim">第 {{ roundNo }} 题</span>
        </div>
        <h2 class="qq">{{ question.question }}</h2>
        <el-input v-model="answer" type="textarea" :rows="7" resize="none"
                  placeholder="像面试那样口述你的答案… 提交后对比标准答案逐点评分" class="ans" />
        <div class="act-row">
          <el-button :disabled="!answer.trim() || grading" :loading="grading" type="primary" @click="doGrade">
            {{ grading ? '考官批改中…' : '提交作答' }}
          </el-button>
          <el-button v-if="answered" :loading="picking" @click="nextQ">下一题</el-button>
          <el-button v-if="answered" text type="danger" @click="finish">结束 · 去复盘</el-button>
        </div>
      </section>

      <!-- 批改结果 -->
      <section v-if="grade" class="card result">
        <div class="result-head">
          <span class="st">批改结果</span>
          <span v-if="!grade.score" class="mock-note">{{ grade.comment }}</span>
        </div>
        <GradePanel :score="grade.score" :hits="grade.hits" :missing="grade.missing" :comment="grade.comment" />
        <div class="std-row">
          <el-button v-if="showStd" link type="primary" @click="stdVisible = !stdVisible">
            {{ stdVisible ? '收起' : '展开' }}标准答案对照
          </el-button>
        </div>
        <div v-if="stdVisible && stdContent" class="std">
          <MarkdownView :content="stdContent" />
        </div>
      </section>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { corpusApi, quizApi } from '../api/client'
import type { GroupNode, Grade, GradeOut, GroupCounts, QuizQuestion } from '../api/types'
import { ElMessage } from 'element-plus'
import GradePanel from '../components/GradePanel.vue'
import MarkdownView from '../components/MarkdownView.vue'

const groups = ref<GroupCounts>({})
const scope = ref<string>('__all__')

const groupLabels: Record<string, string> = {
  java: 'Java', database: '数据库', architecture: '架构设计', tools: '工具命令', ai: 'AI 大模型',
}

const scopes = computed(() => [
  { value: '__all__', label: '全题库' },
  ...Object.keys(groups.value).map((g) => ({ value: g, label: groupLabels[g] || g })),
])

const sessionId = ref('')
const question = ref<QuizQuestion | null>(null)
const answer = ref('')
const grade = ref<Grade | null>(null)
const gradeOut = ref<GradeOut | null>(null)
const starting = ref(false)
const grading = ref(false)
const picking = ref(false)
const roundNo = ref(0)
const stdVisible = ref(false)
const stdContent = ref('')

const answered = computed(() => !!grade.value)

function pickScope(): string | null {
  return scope.value === '__all__' ? null : scope.value
}

async function start() {
  starting.value = true
  try {
    const q = await quizApi.start(pickScope())
    sessionId.value = q.sessionId
    beginRound(q)
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    starting.value = false
  }
}

function beginRound(q: QuizQuestion) {
  question.value = q
  answer.value = ''
  grade.value = null
  stdVisible.value = false
  roundNo.value += 1
}

async function doGrade() {
  if (!sessionId.value || !question.value || !answer.value.trim()) return
  grading.value = true
  try {
    const out = await quizApi.grade(sessionId.value, question.value.chunkId, answer.value.trim())
    grade.value = out.grade
    gradeOut.value = out
    if (question.value) stdContent.value = ''
    const detail = await corpusApi.chunk(question.value.chunkId)
    stdContent.value = detail.content
    stdVisible.value = true
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    grading.value = false
  }
}

async function nextQ() {
  if (!sessionId.value) return
  picking.value = true
  try {
    const q = await quizApi.pick(sessionId.value, pickScope())
    beginRound(q)
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    picking.value = false
  }
}

function finish() {
  sessionId.value = ''
  question.value = null
  grade.value = null
  gradeOut.value = null
  roundNo.value = 0
  void import('../router').then((m) => m.default.push('/records'))
}

onMounted(async () => {
  try {
    groups.value = await corpusApi.groups()
  } catch { /* ignore */ }
})
</script>

<style scoped>
.page { max-width: 820px; margin: 0 auto; display: flex; flex-direction: column; gap: 16px; }
.head h1 { margin: 0 0 4px; font-size: 24px; }
.sub { margin: 0; color: var(--text-dim); font-size: 13.5px; }
.card { border: 1px solid var(--border); border-radius: var(--radius); background: var(--bg-panel); padding: 20px 22px; box-shadow: var(--shadow-panel); }
.set-label { font-size: 13px; color: var(--text-dim); margin: 0 0 10px; }
.scope-row { display: flex; flex-wrap: wrap; gap: 8px; }
.scope { padding: 7px 14px; border-radius: 999px; border: 1px solid var(--border); background: var(--bg-soft); color: var(--text-dim); font-size: 13px; transition: all var(--t-fast) var(--ease); }
.scope.on { background: var(--accent-soft); border-color: var(--accent); color: var(--accent); font-weight: 600; }
.start-row { margin-top: 18px; }

.qmeta { display: flex; gap: 6px; align-items: center; margin-bottom: 10px; }
.tag { font-size: 11px; padding: 2px 8px; border-radius: 999px; background: var(--bg-soft); color: var(--text-dim); }
.tag.accent { background: var(--accent-soft); color: var(--accent); font-weight: 600; }
.tag.dim { color: var(--text-faint); }
.qq { margin: 0 0 14px; font-size: 20px; line-height: 1.5; }
.ans :deep(.el-textarea__inner) { background: var(--bg-soft); }
.act-row { margin-top: 14px; display: flex; gap: 10px; align-items: center; }

.result { border-color: color-mix(in srgb, var(--accent) 30%, var(--border)); }
.result-head { display: flex; align-items: center; gap: 12px; margin-bottom: 14px; }
.st { font-weight: 700; font-size: 15px; }
.mock-note { font-size: 12px; color: var(--warn); }
.std-row { margin-top: 8px; }
.std { margin-top: 10px; border-top: 1px dashed var(--border); padding-top: 8px; }

@media (max-width: 720px) {
  .card { padding: 14px 14px; }
  .qq { font-size: 17px; }
  .act-row { flex-wrap: wrap; }
}
</style>
