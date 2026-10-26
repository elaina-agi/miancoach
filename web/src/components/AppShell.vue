<template>
  <div class="shell">
    <header class="topbar">
      <!-- 品牌区和左侧栏同宽,导航从侧栏右边缘开始,和下面内容区的左边线对齐 -->
      <div class="brand-slot">
        <RouterLink to="/chat" class="brand" aria-label="MianCoach 首页">
          <BrandMark :size="26" />
          <span class="brand-name">MianCoach</span>
        </RouterLink>
      </div>

      <nav class="nav" aria-label="主导航">
        <RouterLink v-for="n in nav" :key="n.to" :to="n.to" class="nav-item" :class="{ on: isActive(n.to) }">
          <component :is="n.icon" :size="16" />
          <span>{{ n.label }}</span>
        </RouterLink>
      </nav>

      <div class="bar-right">
        <!-- 服务状态:正常时只是一个小绿点,不抢眼;mock / 索引异常时带文字,需要用户注意 -->
        <Popover>
          <template #trigger="{ toggle }">
            <button class="status-btn" :class="status.level" :title="status.text" @click="toggle">
              <span class="status-dot" />
              <span v-if="status.level !== 'ok'" class="status-text">{{ status.text }}</span>
            </button>
          </template>
          <div class="status-pop">
            <div class="sp-head"><span class="status-dot" :class="status.level" />{{ status.text }}</div>
            <template v-if="meta">
              <dl class="sp-list">
                <dt>生成模型</dt><dd>{{ meta.chatReal ? meta.chatModel : 'Mock（未配置 key）' }}</dd>
                <dt>向量模型</dt><dd>{{ meta.embedReal ? `${meta.embedModel} · ${meta.embedDims}d` : 'Mock（未配置 key）' }}</dd>
                <dt>题库</dt><dd class="num">{{ meta.totalChunks }} 题 · {{ meta.totalSegs }} 个子段 · {{ meta.docs }} 篇</dd>
              </dl>
              <p class="sp-note">{{ meta.index?.message }}</p>
            </template>
            <template v-else>
              <p class="sp-note">读不到后端状态，确认 server 已启动（默认 8080 端口）。</p>
              <button class="btn btn-secondary btn-sm" @click="retryMeta">重试</button>
            </template>
          </div>
        </Popover>

        <button class="btn btn-ghost btn-icon" :title="theme === 'dark' ? '切换到浅色' : '切换到深色'" @click="toggleTheme">
          <Sun v-if="theme === 'dark'" :size="17" />
          <Moon v-else :size="17" />
        </button>

        <!-- authReady 之前什么都不显示:免得已登录用户先看到"登录"闪一下 -->
        <template v-if="authReady">
          <Popover v-if="currentUser" close-on-click>
            <template #trigger="{ toggle }">
              <button class="avatar" :title="displayName" @click="toggle">{{ initial }}</button>
            </template>
            <div class="acct">
              <div class="acct-head">
                <span class="avatar lg">{{ initial }}</span>
                <span class="acct-text">
                  <span class="acct-name">{{ displayName }}</span>
                  <span v-if="currentUser.nickname" class="acct-mail">{{ currentUser.email }}</span>
                </span>
              </div>
              <button class="menu-item" @click="onLogout"><LogOut :size="15" />退出登录</button>
            </div>
          </Popover>
          <button v-else class="btn btn-secondary btn-sm" @click="openLogin()">登录</button>
        </template>
      </div>
    </header>

    <main class="main">
      <RouterView />
    </main>

    <!-- 手机端底部标签栏(桌面隐藏) -->
    <nav class="tabbar" aria-label="主导航">
      <RouterLink v-for="n in nav" :key="n.to" :to="n.to" class="tab" :class="{ on: isActive(n.to) }">
        <component :is="n.icon" :size="20" />
        <span>{{ n.label }}</span>
      </RouterLink>
    </nav>

    <!-- 全局唯一的登录弹框 / 提示 / 确认框,任何地方调 openLogin() / toast / confirm() 都用这几个 -->
    <AuthDialog />
    <ToastHost />
    <ConfirmHost />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { BookOpen, ChartNoAxesColumn, LogOut, MessageSquare, Moon, NotebookPen, Sun } from 'lucide-vue-next'
import BrandMark from './BrandMark.vue'
import AuthDialog from './AuthDialog.vue'
import Popover from './ui/Popover.vue'
import ToastHost from './ui/ToastHost.vue'
import ConfirmHost from './ui/ConfirmHost.vue'
import { authReady, currentUser, logout, openLogin } from '../lib/auth'
import { loadMeta, meta, metaFailed } from '../lib/catalog'
import { theme, toggleTheme } from '../lib/theme'
import { toast } from '../lib/toast'

const route = useRoute()
const nav = [
  { to: '/chat', label: '问答', icon: MessageSquare },
  { to: '/kb', label: '知识库', icon: BookOpen },
  { to: '/quiz', label: '模拟面试', icon: NotebookPen },
  { to: '/records', label: '复盘', icon: ChartNoAxesColumn },
]
// /chat/xxx、/kb/123 也算在对应导航下
const isActive = (to: string) => route.path === to || route.path.startsWith(to + '/')

/**
 * 状态优先级:连不上 > 索引与向量模型不一致(含未索引)> mock > 正常。
 * 索引不一致意味着"检索结果不可信",比 mock 更严重,必须更醒目。
 */
const status = computed(() => {
  const m = meta.value
  if (!m) return metaFailed.value ? { level: 'bad', text: '后端未连接' } : { level: 'idle', text: '连接中' }
  if (!m.index?.compatible) return { level: 'bad', text: m.index?.indexed ? '索引待重建' : '未建索引' }
  if (!m.chatReal || !m.embedReal) return { level: 'warn', text: 'Mock 模式' }
  return { level: 'ok', text: '服务正常' }
})

function retryMeta() {
  void loadMeta()
}

const displayName = computed(() => currentUser.value?.nickname ?? currentUser.value?.email ?? '')
/** 头像里的字:昵称首字,没昵称用邮箱首字母 */
const initial = computed(() => (displayName.value || '?').charAt(0).toUpperCase())

function onLogout() {
  logout()
  toast.success('已退出登录')
}

onMounted(() => { void loadMeta() })
</script>

<style scoped>
.shell { height: 100vh; height: 100dvh; display: flex; flex-direction: column; }

.topbar { flex: none; height: var(--topbar-h); display: flex; align-items: center; padding: 0 14px 0 20px; border-bottom: 1px solid var(--line); background: var(--bg); position: relative; z-index: 50; }
/* 减掉顶栏左内边距,右边缘正好落在侧栏分隔线上;导航再让出 12px,和内容区标题栏的左内边距一致 */
.brand-slot { flex: none; width: calc(var(--side-w) - 20px); display: flex; align-items: center; }
.brand { display: inline-flex; align-items: center; gap: 10px; border-radius: var(--r); }
.brand-name { font-size: 15.5px; font-weight: 650; letter-spacing: -0.015em; }

.nav { display: flex; gap: 2px; margin-left: 12px; }
.nav-item { display: flex; align-items: center; gap: 7px; height: 34px; padding: 0 12px; border-radius: var(--r); color: var(--ink-2); font-weight: 500; transition: background var(--t) var(--ease), color var(--t) var(--ease); }
.nav-item:hover { background: var(--fill); color: var(--ink); }
.nav-item.on { background: var(--fill); color: var(--ink); font-weight: 600; }
.nav-item.on svg { color: var(--accent); }

.bar-right { margin-left: auto; display: flex; align-items: center; gap: 4px; }

.status-btn { display: inline-flex; align-items: center; gap: 7px; height: 30px; padding: 0 10px; border: 0; border-radius: 999px; background: transparent; cursor: pointer; font-size: 12.5px; font-weight: 500; color: var(--ink-2); transition: background var(--t) var(--ease); }
.status-btn:hover { background: var(--fill); }
/* 文字用墨色不用状态色:琥珀色做小字对比度不够,状态靠圆点颜色 + 底色 + 文字本身传达 */
.status-btn.warn { background: var(--mid-soft); color: var(--ink); }
.status-btn.bad { background: var(--bad-soft); color: var(--ink); }
.status-dot { width: 8px; height: 8px; border-radius: 50%; background: var(--ink-3); flex: none; }
.status-btn.ok .status-dot, .status-dot.ok { background: var(--good); box-shadow: 0 0 0 3px var(--good-soft); }
.status-btn.warn .status-dot, .status-dot.warn { background: var(--mid); }
.status-btn.bad .status-dot, .status-dot.bad { background: var(--bad); }

.status-pop { width: 300px; padding: 8px 10px 10px; display: flex; flex-direction: column; gap: 10px; }
.sp-head { display: flex; align-items: center; gap: 8px; font-weight: 600; }
.sp-list { margin: 0; display: grid; grid-template-columns: auto 1fr; gap: 6px 14px; font-size: 13px; }
.sp-list dt { color: var(--ink-3); }
.sp-list dd { margin: 0; color: var(--ink); overflow-wrap: anywhere; }
.sp-note { font-size: 12.5px; color: var(--ink-2); line-height: 1.6; padding-top: 8px; border-top: 1px solid var(--line); }

.avatar { width: 30px; height: 30px; margin-left: 4px; border-radius: 50%; border: 0; display: grid; place-items: center; background: var(--accent-soft); color: var(--accent); font-weight: 650; font-size: 13px; cursor: pointer; }
.avatar.lg { width: 36px; height: 36px; margin: 0; font-size: 15px; cursor: default; flex: none; }
.acct { width: 240px; }
.acct-head { display: flex; align-items: center; gap: 10px; padding: 8px 8px 12px; border-bottom: 1px solid var(--line); margin-bottom: 6px; }
.acct-text { display: flex; flex-direction: column; min-width: 0; line-height: 1.35; }
.acct-name { font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.acct-mail { font-size: 12px; color: var(--ink-3); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.menu-item { width: 100%; display: flex; align-items: center; gap: 8px; height: 34px; padding: 0 8px; border: 0; border-radius: var(--r-sm); background: transparent; color: var(--ink-2); cursor: pointer; text-align: left; }
.menu-item:hover { background: var(--fill); color: var(--ink); }

.main { flex: 1; min-height: 0; position: relative; }

.tabbar { display: none; }

/* 900px 以下侧栏都收成滑出面板了,没有分隔线可对齐,品牌区按内容宽度走 */
@media (max-width: 900px) {
  .brand-slot { width: auto; margin-right: 16px; }
  .nav { margin-left: 0; }
}

@media (max-width: 720px) {
  .topbar { height: calc(var(--topbar-h) - 4px + env(safe-area-inset-top)); padding: env(safe-area-inset-top) 10px 0 16px; }
  .nav { display: none; }
  .status-pop { width: min(300px, calc(100vw - 32px)); }
  .tabbar { flex: none; display: flex; padding: 4px 6px env(safe-area-inset-bottom); border-top: 1px solid var(--line); background: var(--bg); }
  .tab { flex: 1; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 3px; height: 50px; border-radius: var(--r); color: var(--ink-3); font-size: 11px; font-weight: 500; -webkit-tap-highlight-color: transparent; }
  .tab.on { color: var(--ink); }
  .tab.on svg { color: var(--accent); }
}
</style>
