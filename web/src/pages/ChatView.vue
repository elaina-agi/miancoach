<template>
  <div class="page chat-page">
    <!-- 顶部 -->
    <header class="page-head">
      <div>
        <h1>面试问答</h1>
        <p class="sub">用自己的话提问,答案锚定权威题库原文,可追到出处复习。</p>
      </div>
    </header>

    <!-- 空态快捷话题 -->
    <div v-if="messages.length === 0" class="empty">
      <p class="empty-hint">随便问一道,或从高频考点开始:</p>
      <div class="topic-grid">
        <button v-for="t in topics" :key="t" class="topic" @click="send(t)">{{ t }}</button>
      </div>
    </div>

    <!-- 对话流 -->
    <div ref="listEl" class="thread">
      <div v-for="(m, i) in messages" :key="i" class="row" :class="m.role">
        <div class="bubble" :class="m.role">
          <template v-if="m.role === 'assistant'">
            <MarkdownView v-if="m.text" :content="m.text" />
            <!-- 等待期状态:检索中 / 组织回答中。派生自 streaming+refs+text -->
            <div v-if="pendingLabel(m)" class="status">
              <span class="status-dot" />{{ pendingLabel(m) }}
            </div>
            <div v-if="m.streaming && m.text" class="cursor" />
            <div v-if="m.noAnswer" class="no-answer">语料外问题 · 已拒绝作答</div>
            <!-- 引用 -->
            <div v-if="m.refs?.length" class="refs">
              <div class="refs-title">出处 {{ m.refs.length }} 篇 · 点击追读原文</div>
              <div class="refs-grid">
                <button v-for="(r, j) in m.refs" :key="r.chunkId" class="ref" @click="openRef(r)">
                  <span class="ref-idx">{{ j + 1 }}</span>
                  <span class="ref-body">
                    <span class="ref-q">{{ r.question }}</span>
                    <span class="ref-meta">{{ r.group }} · {{ r.title }}</span>
                  </span>
                </button>
              </div>
            </div>
          </template>
          <template v-else>
            <p class="user-text">{{ m.text }}</p>
          </template>
        </div>
      </div>
    </div>

    <!-- 输入 -->
    <div class="composer-wrap">
      <div class="composer">
        <el-input v-model="input" type="textarea" :autosize="{ minRows: 1, maxRows: 5 }"
                  resize="none" placeholder="输入问题,回车发送(Shift+回车换行)"
                  @keydown.enter.exact.prevent="send()" />
        <el-button v-if="!streaming" type="primary" circle class="send-btn" :disabled="!input.trim()" @click="send()">
          <el-icon :size="16"><Promotion /></el-icon>
        </el-button>
        <el-button v-else circle class="stop-btn" @click="stop()">
          <el-icon :size="15"><VideoPause /></el-icon>
        </el-button>
      </div>
    </div>

    <!-- 追读抽屉 -->
    <el-drawer v-model="drawer" :size="min(560, windowW * 0.9)" :with-header="false" class="ref-drawer">
      <div v-if="current" class="ref-panel">
        <div class="ref-head">
          <div class="ref-head-tag">{{ current.group }} · {{ current.category || '综合' }}</div>
          <h2 class="ref-q">{{ current.question }}</h2>
          <div class="ref-head-meta">{{ current.title }}</div>
        </div>
        <div class="ref-body-scroll">
          <MarkdownView v-if="detail" :content="detail.content" />
        </div>
        <div class="ref-foot">
          <el-button link type="primary" tag="a" :href="current.url" target="_blank">
            查看原站文章 <el-icon><TopRight /></el-icon>
          </el-button>
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { nextTick, onBeforeUnmount, ref } from 'vue'
import { askStream } from '../api/client'
import { corpusApi } from '../api/client'
import type { AskRef, ChunkFull } from '../api/types'
import { ElMessage } from 'element-plus'
import { Promotion, VideoPause, TopRight } from '@element-plus/icons-vue'
import MarkdownView from '../components/MarkdownView.vue'
import { createSmoothAppender, type SmoothAppender } from '../lib/smoothStream'

interface Msg { role: 'user' | 'assistant'; text: string; refs?: AskRef[]; streaming?: boolean; noAnswer?: boolean }

const topics = [
  'String 为什么不可变?',
  'HashMap 的底层原理?',
  '线程池核心参数有哪些?',
  'JVM 内存区域怎么划分?',
  'Redis 缓存穿透怎么解决?',
  'Spring IoC 是什么?',
]

const messages = ref<Msg[]>([])
const input = ref('')
const streaming = ref(false)
const listEl = ref<HTMLElement | null>(null)
let abort: (() => void) | null = null
// 去抖动渲染器:和 abort 一样提到函数外,stop() 与组件卸载时要能停掉它
let appender: SmoothAppender | null = null

const drawer = ref(false)
const current = ref<AskRef | null>(null)
const detail = ref<ChunkFull | null>(null)
const windowW = ref(window.innerWidth)
function min(a: number, b: number) { return a < b ? a : b }

/**
 * 等待期的状态文案。**派生自现有状态,不新增 phase 字段**——
 * 加了字段就要在 onDone/onError/stop 三处记得清空,漏一处状态就卡住;
 * 派生的话条件不成立自动消失,不可能不同步。
 *   还没 refs           → 检索题库中(含多轮追问改写那一步,用户不需要区分)
 *   有 refs 但还没出字   → 已找到 n 篇材料,正在组织回答
 *   出字了 / 流已结束    → null,状态消失
 */
function pendingLabel(m: Msg): string | null {
  if (!m.streaming || m.text.length > 0) return null
  return m.refs?.length ? `已找到 ${m.refs.length} 篇材料,正在组织回答…` : '检索题库中…'
}

async function openRef(r: AskRef) {
  current.value = r
  detail.value = null
  drawer.value = true
  try {
    detail.value = await corpusApi.chunk(r.chunkId)
  } catch (e) {
    ElMessage.error('原文加载失败: ' + (e as Error).message)
  }
}

function scrollBottom() {
  void nextTick(() => {
    listEl.value?.scrollTo({ top: listEl.value.scrollHeight, behavior: 'smooth' })
  })
}

async function send(text?: string) {
  const q = (text ?? input.value).trim()
  if (!q || streaming.value) return
  input.value = ''
  // 取上一轮问答当 history(后端据此把追问里的指代改写成独立问题再检索)
  let history: { q: string; a: string }[] = []
  for (let i = messages.value.length - 1; i >= 0; i--) {
    const m = messages.value[i]
    if (m.role === 'assistant' && !m.streaming && m.text) {
      const prev = messages.value[i - 1]
      if (prev && prev.role === 'user') history = [{ q: prev.text, a: m.text }]
      break
    }
  }
  messages.value.push({ role: 'user', text: q })
  messages.value.push({ role: 'assistant', text: '', refs: [], streaming: true })
  // ★ 必须从数组里"读回来"拿到响应式代理。ref([]) 是深层响应式,但 push 进去的是原始对象,
  //   数组只在读取时才把它包成 proxy —— 直接改 push 前的那个局部变量会绕过 proxy 的 set 陷阱,
  //   流式期间一个字都不会渲染,直到 streaming.value=false 触发重渲染才整段刷出来。
  const msg = messages.value[messages.value.length - 1]
  streaming.value = true
  scrollBottom()
  // delta 的到达间隔很不均匀(实测 0~574ms / 单块 0~47 字),直接 append 会一顿一顿。
  // 改成"入队 + 按帧匀速出队",真正写进 msg.text 的时机由 rAF 控制。
  appender = createSmoothAppender((chunk) => {
    msg.text += chunk
    scrollBottom()
  })
  abort = askStream(q, {
    onRefs: (refs) => {
      msg.refs = refs
      scrollBottom()
    },
    onDelta: (t) => {
      appender?.push(t)
    },
    onDone: (info) => {
      msg.noAnswer = info?.noAnswer
      // 收尾挂在"缓冲区排空后",不是挂在 done 上——
      // 否则最后一两百毫秒里字还在画,光标已经停了、按钮已经解锁了。
      appender?.finish(() => {
        msg.streaming = false
        streaming.value = false
        scrollBottom()
      })
    },
    onError: (e) => {
      appender?.flushNow()   // 出错就别慢慢画了,剩余一次性吐完
      msg.streaming = false
      streaming.value = false
      msg.text = '出错了:' + e
      ElMessage.error(e)
    },
  }, history)
}

function stop() {
  abort?.()
  appender?.cancel()   // 停掉 rAF 循环,丢弃还没画出来的字
  const last = messages.value[messages.value.length - 1]
  if (last) last.streaming = false
  streaming.value = false
}

onBeforeUnmount(() => {
  abort?.()
  appender?.cancel()   // 否则 rAF 回调会继续操作已卸载组件的状态
})
</script>

<style scoped>
.chat-page { display: flex; flex-direction: column; height: 100%; max-width: 900px; margin: 0 auto; }
.page-head { padding-bottom: 10px; }
h1 { margin: 0 0 4px; font-size: 24px; letter-spacing: -0.4px; }
.sub { margin: 0; color: var(--text-dim); font-size: 13.5px; }

.thread { flex: 1; overflow-y: auto; padding: 8px 2px 16px; display: flex; flex-direction: column; gap: 14px; }
.row { display: flex; }
.row.user { justify-content: flex-end; }
.bubble { max-width: 88%; border-radius: var(--radius); padding: 11px 14px; }
.bubble.user {
  background: linear-gradient(135deg, var(--accent), #15803d);
  color: #04170c;
}
.user-text { margin: 0; white-space: pre-wrap; font-weight: 500; }
.bubble.assistant {
  background: var(--bg-panel);
  border: 1px solid var(--border);
  box-shadow: var(--shadow-panel);
  width: 100%;
}
.cursor {
  display: inline-block; width: 2px; height: 1em; vertical-align: -0.15em;
  background: var(--accent); animation: blink 1s step-start infinite;
}
.status { display: inline-flex; align-items: center; gap: 8px; font-size: 13px; color: var(--text-dim); }
.status-dot { width: 6px; height: 6px; border-radius: 50%; background: var(--accent); animation: breathe 1.4s ease-in-out infinite; }
@keyframes breathe { 0%, 100% { opacity: 0.25; transform: scale(0.8); } 50% { opacity: 1; transform: scale(1); } }
.no-answer {
  margin-top: 8px; display: inline-block; font-size: 11.5px; font-weight: 600;
  color: var(--text-dim); background: var(--bg-soft); border: 1px dashed var(--border-strong);
  border-radius: 7px; padding: 3px 8px;
}
@keyframes blink { 50% { opacity: 0; } }

.refs { margin-top: 12px; border-top: 1px dashed var(--border); padding-top: 10px; }
.refs-title { font-size: 11.5px; color: var(--text-faint); margin-bottom: 7px; }
.refs-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(230px, 1fr)); gap: 7px; }
.ref {
  display: flex; gap: 8px; align-items: flex-start; text-align: left;
  border: 1px solid var(--border); background: var(--bg-soft); border-radius: 9px; padding: 7px 9px;
  transition: border-color var(--t-fast) var(--ease), transform var(--t-fast) var(--ease);
}
.ref:hover { border-color: var(--accent); transform: translateY(-1px); }
.ref-idx {
  flex: none; width: 18px; height: 18px; border-radius: 6px; display: grid; place-items: center;
  background: var(--accent-soft); color: var(--accent); font-size: 11px; font-weight: 700; font-family: var(--font-display);
}
.ref-body { display: flex; flex-direction: column; min-width: 0; }
.ref-q { font-size: 12px; color: var(--text); line-height: 1.35; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.ref-meta { font-size: 10.5px; color: var(--text-faint); margin-top: 2px; }

.composer-wrap { position: sticky; bottom: 0; background: linear-gradient(transparent, var(--bg) 30%); padding-top: 10px; }
.composer {
  display: flex; gap: 8px; align-items: flex-end;
  background: var(--bg-panel); border: 1px solid var(--border-strong); border-radius: 14px; padding: 8px;
  box-shadow: var(--shadow-panel);
}
.composer :deep(.el-textarea__inner) { background: transparent; border: 0; box-shadow: none; color: var(--text); }
.send-btn { background: var(--accent); border: 0; }
.send-btn:hover { background: var(--accent-strong); }
.stop-btn { background: var(--bg-soft); border: 1px solid var(--border); }

.empty { margin: 26px 0; }
.empty-hint { color: var(--text-dim); font-size: 13px; }
.topic-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(230px, 1fr)); gap: 8px; }
.topic {
  text-align: left; padding: 10px 12px; font-size: 13px; color: var(--text);
  background: var(--bg-panel); border: 1px solid var(--border); border-radius: 10px;
  transition: all var(--t-fast) var(--ease);
}
.topic:hover { border-color: var(--accent); background: var(--accent-soft); }

.ref-panel { display: flex; flex-direction: column; height: 100%; }
.ref-head { border-bottom: 1px solid var(--border); padding-bottom: 12px; }
.ref-head-tag { font-size: 11px; color: var(--accent); font-weight: 600; }
.ref-q { margin: 6px 0 4px; font-size: 18px; line-height: 1.4; }
.ref-head-meta { color: var(--text-faint); font-size: 12px; }
.ref-body-scroll { flex: 1; overflow-y: auto; padding: 14px 2px; }
.ref-foot { border-top: 1px solid var(--border); padding-top: 10px; }
</style>
