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
            <div v-if="m.streaming" class="cursor" />
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

interface Msg { role: 'user' | 'assistant'; text: string; refs?: AskRef[]; streaming?: boolean }

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

const drawer = ref(false)
const current = ref<AskRef | null>(null)
const detail = ref<ChunkFull | null>(null)
const windowW = ref(window.innerWidth)
function min(a: number, b: number) { return a < b ? a : b }

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
  messages.value.push({ role: 'user', text: q })
  const msg: Msg = { role: 'assistant', text: '', refs: [], streaming: true }
  messages.value.push(msg)
  streaming.value = true
  scrollBottom()
  abort = askStream(q, {
    onRefs: (refs) => {
      msg.refs = refs
      scrollBottom()
    },
    onDelta: (t) => {
      msg.text += t
      scrollBottom()
    },
    onDone: () => {
      msg.streaming = false
      streaming.value = false
      scrollBottom()
    },
    onError: (e) => {
      msg.streaming = false
      streaming.value = false
      msg.text = '出错了:' + e
      ElMessage.error(e)
    },
  })
}

function stop() {
  abort?.()
  const last = messages.value[messages.value.length - 1]
  if (last) last.streaming = false
  streaming.value = false
}

onBeforeUnmount(() => abort?.())
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
