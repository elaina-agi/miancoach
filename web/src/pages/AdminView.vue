<template>
  <div class="page-scroll">
    <div class="admin">
      <header class="intro">
        <h1>管理后台</h1>
        <p>重建题库索引、管理注册用户。</p>
      </header>

      <!-- 页面自己按登录态拦,不在路由守卫里拦(见 router);真正的权限校验在后端 -->
      <div v-if="!authReady" class="gate"><span class="dots"><i /><i /><i /></span></div>
      <div v-else-if="!currentUser" class="gate card">
        <Lock :size="20" class="gate-icon" />
        <p>需要用管理员账号登录</p>
        <button class="btn btn-primary" @click="openLogin()"><LogIn :size="15" />登录</button>
      </div>
      <div v-else-if="!isAdmin" class="gate card">
        <ShieldOff :size="20" class="gate-icon" />
        <p>当前账号没有管理员权限</p>
        <RouterLink to="/chat" class="btn btn-secondary">回到问答</RouterLink>
      </div>

      <template v-else>
        <!-- ── 题库索引 ── -->
        <section class="panel" aria-labelledby="sec-index">
          <div class="panel-head">
            <Database :size="18" class="head-icon" />
            <h2 id="sec-index">题库索引</h2>
            <span v-if="meta" class="tag" :class="indexTag.cls">{{ indexTag.text }}</span>
          </div>

          <dl v-if="meta" class="kv">
            <dt>题库</dt>
            <dd class="num">{{ meta.docs }} 篇 · {{ meta.totalChunks }} 题 · {{ meta.totalSegs }} 个子段</dd>
            <dt>向量模型</dt>
            <dd>{{ meta.index.indexedModel ?? '—' }}<template v-if="meta.index.indexedDims"> · {{ meta.index.indexedDims }}d</template></dd>
            <dt>上次重建</dt>
            <dd>{{ meta.index.indexedAt ? friendlyDate(meta.index.indexedAt) : '还没建过' }}</dd>
          </dl>
          <p v-if="meta && !meta.index.compatible" class="note bad">{{ meta.index.message }}</p>

          <div v-if="job.state === 'RUNNING'" class="job">
            <div class="job-top">
              <span>正在重建 · {{ job.phase === '子段' ? '子段向量化' : job.phase === '题目' ? '切题入库' : '准备中' }}</span>
              <span v-if="job.total" class="num">{{ job.done }} / {{ job.total }}</span>
            </div>
            <div class="bar" role="progressbar" :aria-valuenow="percent" aria-valuemin="0" aria-valuemax="100">
              <i :style="{ width: `${percent}%` }" />
            </div>
            <p class="hint">已用 {{ elapsed }}。可以离开这个页面，重建在服务器上继续进行；期间网站照常可用。</p>
          </div>
          <p v-else-if="job.state === 'SUCCEEDED' && job.result" class="note good">
            <CircleCheck :size="15" />
            {{ job.finishedAt ? friendlyDate(job.finishedAt) : '' }} 重建完成，用时 {{ elapsed }}：
            {{ job.result.docs }} 篇 / {{ job.result.chunks }} 题 / {{ job.result.segs }} 个子段
          </p>
          <p v-else-if="job.state === 'FAILED'" class="note bad">
            <CircleAlert :size="15" />重建失败，已回滚，库里仍是原来的索引：{{ job.error }}
          </p>

          <div class="actions">
            <button class="btn btn-secondary" :disabled="job.state === 'RUNNING' || starting" @click="startReindex">
              <RefreshCw :size="15" :class="{ spin: job.state === 'RUNNING' || starting }" />重建索引
            </button>
            <span class="hint">语料更新、换了向量模型或分块方案之后需要重建</span>
          </div>
        </section>

        <!-- ── 注册用户 ── -->
        <section class="panel" aria-labelledby="sec-users">
          <div class="panel-head">
            <Users :size="18" class="head-icon" />
            <h2 id="sec-users">注册用户</h2>
            <span class="count num">{{ total }} 人</span>
          </div>

          <label class="search">
            <Search :size="15" class="search-icon" />
            <input v-model.trim="q" class="input" type="search" placeholder="搜索邮箱或昵称" aria-label="搜索用户" />
          </label>

          <ul v-if="users.length" class="users">
            <li v-for="u in users" :key="u.id" class="user" :class="{ off: u.disabledAt }">
              <span class="u-avatar">{{ (u.nickname || u.email).charAt(0).toUpperCase() }}</span>
              <div class="u-main">
                <div class="u-name">
                  <span class="name">{{ u.nickname ?? u.email }}</span>
                  <span v-if="isSelf(u)" class="tag">你</span>
                  <span v-if="u.role === 'ADMIN'" class="tag tag-accent">管理员</span>
                  <span v-if="u.disabledAt" class="tag bad">已禁用</span>
                </div>
                <div class="u-sub">
                  <template v-if="u.nickname">{{ u.email }} · </template>注册于 {{ friendlyDate(u.createdAt, false) }} ·
                  <!-- 登录时间是 V8 才开始记的,之前登录过的老用户这里也是空的,所以不能写"从没登录过" -->
                  {{ u.lastLoginAt ? `最近登录 ${friendlyDate(u.lastLoginAt)}` : '暂无登录记录' }}
                </div>
              </div>
              <div class="u-stats num">
                <span>{{ u.chatSessions }} 次问答</span>
                <span>{{ u.quizSessions }} 场面试</span>
              </div>
              <Popover close-on-click>
                <template #trigger="{ toggle }">
                  <button class="btn btn-ghost btn-icon btn-sm" :aria-label="`管理 ${u.email}`" @click="toggle">
                    <Ellipsis :size="16" />
                  </button>
                </template>
                <button class="menu-item" @click="editNickname(u)"><PenLine :size="15" />修改昵称</button>
                <!-- 对自己只能改昵称:不能禁用 / 删除 / 取消管理员,后端也拦着 -->
                <template v-if="!isSelf(u)">
                  <button class="menu-item" @click="toggleRole(u)">
                    <template v-if="u.role === 'ADMIN'"><ShieldOff :size="15" />取消管理员</template>
                    <template v-else><Shield :size="15" />设为管理员</template>
                  </button>
                  <button class="menu-item" @click="toggleDisabled(u)">
                    <template v-if="u.disabledAt"><UserCheck :size="15" />解除禁用</template>
                    <template v-else><Ban :size="15" />禁用账号</template>
                  </button>
                  <button class="menu-item danger" @click="removeUser(u)"><Trash2 :size="15" />删除用户</button>
                </template>
              </Popover>
            </li>
          </ul>
          <div v-else-if="usersLoading" class="empty"><span class="dots"><i /><i /><i /></span></div>
          <p v-else class="empty">{{ q ? '没有匹配的用户' : '还没有注册用户' }}</p>

          <div v-if="pages > 1" class="pager">
            <button class="btn btn-ghost btn-sm" :disabled="page <= 1" @click="page--"><ChevronLeft :size="15" />上一页</button>
            <span class="num">{{ page }} / {{ pages }}</span>
            <button class="btn btn-ghost btn-sm" :disabled="page >= pages" @click="page++">下一页<ChevronRight :size="15" /></button>
          </div>
        </section>
      </template>
    </div>

    <Modal v-model:open="nickOpen" title="修改昵称" :width="380">
      <form class="nick-form" @submit.prevent="saveNickname">
        <p class="nick-for">{{ editing?.email }}</p>
        <input v-model="nickInput" class="input" maxlength="20" placeholder="留空 = 清空昵称，改为显示邮箱" />
      </form>
      <template #footer>
        <button class="btn btn-secondary" @click="nickOpen = false">取消</button>
        <button class="btn btn-primary" :disabled="saving" @click="saveNickname">保存</button>
      </template>
    </Modal>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import {
  Ban, ChevronLeft, ChevronRight, CircleAlert, CircleCheck, Database, Ellipsis, Lock, LogIn, PenLine, RefreshCw,
  Search, Shield, ShieldOff, Trash2, UserCheck, Users,
} from 'lucide-vue-next'
import Modal from '../components/ui/Modal.vue'
import Popover from '../components/ui/Popover.vue'
import { ApiError, adminApi } from '../api/client'
import type { AdminUser, ReindexStatus } from '../api/types'
import { authReady, currentUser, isAdmin, openLogin } from '../lib/auth'
import { loadMeta, meta, reloadCorpus } from '../lib/catalog'
import { confirm } from '../lib/confirm'
import { friendlyDate } from '../lib/format'
import { toast } from '../lib/toast'

const errMsg = (e: unknown) => (e as Error).message

// ── 题库索引 ──

const job = ref<ReindexStatus>({ state: 'IDLE', done: 0, total: 0 })
const starting = ref(false)
const now = ref(Date.now())
let pollTimer: number | undefined

const indexTag = computed(() => {
  const idx = meta.value?.index
  if (!idx?.indexed) return { text: '未建索引', cls: 'bad' }
  return idx.compatible ? { text: '正常', cls: 'good' } : { text: '待重建', cls: 'bad' }
})

const percent = computed(() => (job.value.total ? Math.round((job.value.done / job.value.total) * 100) : 0))

/** 跑的时候是"已经跑了多久",跑完是"总共用了多久" */
const elapsed = computed(() => {
  const { startedAt, finishedAt } = job.value
  if (!startedAt) return ''
  const end = finishedAt ? new Date(finishedAt).getTime() : now.value
  const secs = Math.max(0, Math.round((end - new Date(startedAt).getTime()) / 1000))
  return secs < 60 ? `${secs} 秒` : `${Math.floor(secs / 60)} 分 ${secs % 60} 秒`
})

/** 跑着的时候每 1.5 秒查一次进度;网络抖一下就下一轮再查,退出登录 / 被取消管理员后停 */
function schedulePoll() {
  clearTimeout(pollTimer)
  if (!isAdmin.value) return
  pollTimer = window.setTimeout(() => { void refreshJob().catch(schedulePoll) }, 1500)
}

async function refreshJob(): Promise<void> {
  const before = job.value.state
  job.value = await adminApi.reindexStatus()
  now.value = Date.now()
  if (job.value.state === 'RUNNING') {
    schedulePoll()
  } else if (before === 'RUNNING') {
    // 刚跑完:题目 id 全变了,各页面缓存的题库树、题数一起刷新
    if (job.value.state === 'SUCCEEDED') {
      toast.success('索引重建完成')
      void reloadCorpus()
    } else {
      toast.error('索引重建失败')
    }
  }
}

async function startReindex() {
  const ok = await confirm({
    title: '重建索引？',
    message: '会把题库全部重新切分、重新向量化，大约要几分钟，并消耗向量接口的调用额度。重建期间网站照常可用；'
      + '完成后题目 id 会全部重新生成，之前分享的题目链接、复盘里跳转原题的链接会失效。',
    confirmText: '开始重建',
  })
  if (!ok) return
  starting.value = true
  try {
    job.value = await adminApi.reindex()
  } catch (e) {
    toast.error(errMsg(e)) // 409:别人(或另一个标签页)已经开始了,下面照样接上进度
  } finally {
    starting.value = false
  }
  await refreshJob().catch(schedulePoll)
}

// ── 注册用户 ──

const PAGE_SIZE = 20
const users = ref<AdminUser[]>([])
const total = ref(0)
const page = ref(1)
const q = ref('')
const usersLoading = ref(false)
const pages = computed(() => Math.max(1, Math.ceil(total.value / PAGE_SIZE)))
let seq = 0

const isSelf = (u: AdminUser) => u.id === currentUser.value?.id

async function loadUsers(): Promise<void> {
  const my = ++seq // 搜索框连续输入时,只认最后一次请求的结果
  usersLoading.value = true
  try {
    const res = await adminApi.users(q.value, page.value, PAGE_SIZE)
    if (my !== seq) return
    users.value = res.items
    total.value = res.total
    // 删掉了最后一页的最后一个人:退回上一页(page 变化会再触发一次加载)
    if (!res.items.length && page.value > 1) page.value = pages.value
  } catch (e) {
    if (my === seq) toast.error(errMsg(e))
  } finally {
    if (my === seq) usersLoading.value = false
  }
}

let searchTimer: number | undefined
watch(q, () => {
  clearTimeout(searchTimer)
  searchTimer = window.setTimeout(() => {
    if (page.value === 1) void loadUsers()
    else page.value = 1
  }, 300)
})
watch(page, () => { void loadUsers() })

/** 改完一个用户:提示 + 刷新当前页 */
async function act(run: () => Promise<unknown>, done: string) {
  try {
    await run()
    toast.success(done)
  } catch (e) {
    toast.error(errMsg(e))
  }
  await loadUsers()
}

async function toggleRole(u: AdminUser) {
  const toAdmin = u.role !== 'ADMIN'
  const ok = await confirm(toAdmin
    ? {
        title: `把 ${u.email} 设为管理员？`,
        message: '管理员可以重建索引，也可以禁用、删除其他用户，包括其他管理员。',
        confirmText: '设为管理员',
      }
    : { title: `取消 ${u.email} 的管理员身份？`, message: '立即生效，对方再打开管理后台会提示没有权限。', confirmText: '取消管理员' })
  if (!ok) return
  await act(() => adminApi.setRole(u.id, toAdmin ? 'ADMIN' : 'USER'), toAdmin ? '已设为管理员' : '已取消管理员')
}

async function toggleDisabled(u: AdminUser) {
  if (u.disabledAt) {
    await act(() => adminApi.setDisabled(u.id, false), '已解除禁用')
    return
  }
  const ok = await confirm({
    title: `禁用 ${u.email}？`,
    message: '禁用后对方会立即退出登录，也无法再登录。问答和面试记录会保留，随时可以解除禁用。',
    confirmText: '禁用',
    danger: true,
  })
  if (ok) await act(() => adminApi.setDisabled(u.id, true), '已禁用')
}

async function removeUser(u: AdminUser) {
  const ok = await confirm({
    title: `删除 ${u.email}？`,
    message: `该用户的 ${u.chatSessions} 次问答、${u.quizSessions} 场面试记录会一起删除，不可恢复。`
      + '只是不想让对方继续使用的话，用「禁用」就行。',
    confirmText: '删除',
    danger: true,
  })
  if (ok) await act(() => adminApi.deleteUser(u.id), '已删除')
}

// ── 修改昵称 ──

const nickOpen = ref(false)
const editing = ref<AdminUser | null>(null)
const nickInput = ref('')
const saving = ref(false)

function editNickname(u: AdminUser) {
  editing.value = u
  nickInput.value = u.nickname ?? ''
  nickOpen.value = true
}

async function saveNickname() {
  const u = editing.value
  if (!u || saving.value) return
  saving.value = true
  try {
    await adminApi.setNickname(u.id, nickInput.value.trim())
    nickOpen.value = false
    toast.success(nickInput.value.trim() ? '昵称已修改' : '昵称已清空')
    // 改的是自己:顶栏的名字跟着变
    if (isSelf(u) && currentUser.value) currentUser.value = { ...currentUser.value, nickname: nickInput.value.trim() || undefined }
    await loadUsers()
  } catch (e) {
    toast.error(errMsg(e))
  } finally {
    saving.value = false
  }
}

// ── 进页面 / 权限变化 ──

watch(isAdmin, (admin) => {
  clearTimeout(pollTimer)
  if (!admin) return
  void loadMeta()
  void loadUsers()
  void refreshJob().catch((e) => {
    if (!(e instanceof ApiError && e.status === 401)) toast.error(errMsg(e))
  })
}, { immediate: true })

onBeforeUnmount(() => {
  clearTimeout(pollTimer)
  clearTimeout(searchTimer)
})
</script>

<style scoped>
.page-scroll { height: 100%; overflow-y: auto; }
.admin { max-width: 860px; margin: 0 auto; padding: 40px 28px 64px; display: flex; flex-direction: column; gap: 16px; }

.intro { margin-bottom: 12px; }
.intro h1 { font-size: 28px; letter-spacing: -0.02em; }
.intro p { margin-top: 8px; font-size: 15px; line-height: 1.7; color: var(--ink-2); }

.gate { display: flex; flex-direction: column; align-items: center; gap: 12px; padding: 40px 20px; text-align: center; color: var(--ink-2); }
.gate-icon { color: var(--ink-3); }

.panel { padding: 20px 22px; border: 1px solid var(--line); border-radius: var(--r-lg); background: var(--surface); display: flex; flex-direction: column; gap: 14px; }
.panel-head { display: flex; align-items: center; gap: 8px; }
.panel-head h2 { font-size: 15.5px; }
.head-icon { color: var(--accent); }
.count { margin-left: auto; font-size: 13px; color: var(--ink-3); }

.tag.good { background: var(--good-soft); color: var(--good); }
.tag.bad { background: var(--bad-soft); color: var(--bad); }

.kv { margin: 0; display: grid; grid-template-columns: auto 1fr; gap: 6px 18px; font-size: 14px; }
.kv dt { color: var(--ink-3); }
.kv dd { margin: 0; color: var(--ink); overflow-wrap: anywhere; }

.note { display: flex; align-items: flex-start; gap: 6px; padding: 10px 12px; border-radius: var(--r); font-size: 13.5px; line-height: 1.65; }
.note svg { margin-top: 3px; }
.note.good { background: var(--good-soft); color: var(--good); }
.note.bad { background: var(--bad-soft); color: var(--bad); }

.job { display: flex; flex-direction: column; gap: 8px; padding: 12px 14px; border: 1px solid var(--line); border-radius: var(--r); background: var(--bg); }
.job-top { display: flex; justify-content: space-between; gap: 12px; font-size: 13.5px; font-weight: 500; }
.bar { height: 6px; border-radius: 999px; background: var(--fill-strong); overflow: hidden; }
.bar i { display: block; height: 100%; border-radius: inherit; background: var(--accent); transition: width 400ms var(--ease); }

.actions { display: flex; align-items: center; flex-wrap: wrap; gap: 12px; }
.hint { font-size: 12.5px; color: var(--ink-3); }

.search { position: relative; display: block; }
.search-icon { position: absolute; left: 12px; top: 50%; transform: translateY(-50%); color: var(--ink-3); pointer-events: none; }
.search .input { padding-left: 34px; }

.users { margin: 0; padding: 0; list-style: none; display: flex; flex-direction: column; }
.user { display: flex; align-items: center; gap: 12px; padding: 12px 4px; border-top: 1px solid var(--line); }
.user:first-child { border-top: 0; }
.user.off .u-avatar, .user.off .name { opacity: 0.55; }
.u-avatar { flex: none; width: 34px; height: 34px; border-radius: 50%; display: grid; place-items: center; background: var(--accent-soft); color: var(--accent); font-weight: 650; font-size: 14px; }
.u-main { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 2px; }
.u-name { display: flex; align-items: center; gap: 6px; min-width: 0; }
.name { font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.u-sub { font-size: 12.5px; color: var(--ink-3); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.u-stats { flex: none; display: flex; flex-direction: column; align-items: flex-end; gap: 2px; font-size: 12.5px; color: var(--ink-2); }

.menu-item { width: 100%; display: flex; align-items: center; gap: 8px; height: 34px; padding: 0 8px; border: 0; border-radius: var(--r-sm); background: transparent; color: var(--ink-2); cursor: pointer; text-align: left; }
.menu-item:hover { background: var(--fill); color: var(--ink); }
.menu-item.danger { color: var(--bad); }
.menu-item.danger:hover { background: var(--bad-soft); color: var(--bad); }

.empty { display: flex; justify-content: center; padding: 28px 0; font-size: 13.5px; color: var(--ink-3); }

.pager { display: flex; align-items: center; justify-content: center; gap: 12px; font-size: 13px; color: var(--ink-2); }

.nick-form { display: flex; flex-direction: column; gap: 10px; }
.nick-for { font-size: 13px; color: var(--ink-3); overflow-wrap: anywhere; }

@media (max-width: 720px) {
  .admin { padding: 24px 16px 40px; }
  .intro h1 { font-size: 22px; }
  .panel { padding: 16px; }
  .u-sub { white-space: normal; }
  .u-stats { display: none; }
}
</style>
