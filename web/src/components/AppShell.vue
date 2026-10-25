<template>
  <div class="shell">
    <!-- 手机端顶栏(桌面隐藏):品牌 + 主题/账号 -->
    <header class="mobile-topbar">
      <div class="m-brand" @click="router.push('/chat')">
        <div class="brand-mark"><el-icon :size="18"><Monitor /></el-icon></div>
        <span class="m-name">MianCoach</span>
      </div>
      <div class="m-actions">
        <button class="ghost-btn" title="切换深浅色" @click="toggle">
          <el-icon :size="16"><component :is="isDarkNow ? 'Moon' : 'Sunny'" /></el-icon>
        </button>
        <template v-if="authReady">
          <template v-if="currentUser">
            <span class="m-account">{{ currentUser.nickname ?? currentUser.email }}</span>
            <button class="ghost-btn" title="退出登录" @click="onLogout">
              <el-icon :size="15"><SwitchButton /></el-icon>
            </button>
          </template>
          <button v-else class="login-btn" @click="openLogin()">
            <el-icon :size="14"><User /></el-icon>
            <span>登录</span>
          </button>
        </template>
      </div>
    </header>

    <!-- 左导航(桌面端) -->
    <aside class="rail">
      <div class="brand" @click="router.push('/chat')">
        <div class="brand-mark"><el-icon :size="20"><Monitor /></el-icon></div>
        <div class="brand-text">
          <span class="brand-name">MianCoach</span>
          <span class="brand-sub">AI 模拟面试官</span>
        </div>
      </div>

      <nav class="nav">
        <router-link v-for="item in items" :key="item.to" :to="item.to"
                     class="nav-item" active-class="active">
          <el-icon :size="18"><component :is="item.icon" /></el-icon>
          <span>{{ item.label }}</span>
          <span v-if="item.badge && groupCounts" class="badge">{{ groupCounts[item.group] ?? '' }}</span>
        </router-link>
      </nav>

      <!-- 账号:游客显示登录入口,已登录显示昵称。authReady 之前什么都不显示,免得已登录用户看到"登录"闪一下 -->
      <div v-if="authReady" class="account">
        <template v-if="currentUser">
          <div class="avatar">{{ initial }}</div>
          <div class="account-text">
            <span class="account-name">{{ currentUser.nickname ?? currentUser.email }}</span>
            <span v-if="currentUser.nickname" class="account-sub">{{ currentUser.email }}</span>
          </div>
          <el-tooltip content="退出登录" placement="top" :show-after="200">
            <button class="ghost-btn account-out" @click="onLogout">
              <el-icon :size="15"><SwitchButton /></el-icon>
            </button>
          </el-tooltip>
        </template>
        <button v-else class="login-btn" @click="openLogin()">
          <el-icon :size="16"><User /></el-icon>
          <span>登录 / 注册</span>
        </button>
      </div>

      <div class="rail-foot">
        <el-tooltip :content="chip.tip" placement="top" :show-after="200">
          <div class="chip" :class="chip.cls">
            <span class="dot" />
            {{ chip.text }}
          </div>
        </el-tooltip>
        <div class="chip-dim">题库 {{ meta?.totalChunks ?? '…' }} 题</div>
        <button class="ghost-btn" title="切换深浅色" @click="toggle">
          <el-icon :size="15"><component :is="isDarkNow ? 'Moon' : 'Sunny'" /></el-icon>
        </button>
      </div>
    </aside>

    <!-- 主区 -->
    <main class="content">
      <router-view />
    </main>

    <!-- 手机端底部标签栏(桌面隐藏) -->
    <nav class="mobile-tabbar">
      <router-link v-for="item in items" :key="item.to" :to="item.to"
                   class="tab" active-class="on">
        <el-icon :size="20"><component :is="item.icon" /></el-icon>
        <span>{{ item.label }}</span>
      </router-link>
    </nav>

    <!-- 全局唯一的登录弹框,任何地方调 requireLogin()/openLogin() 都打开它 -->
    <AuthDialog />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  ChatDotRound, Collection, Aim, DataAnalysis, Monitor, Moon, Sunny, SwitchButton, User,
} from '@element-plus/icons-vue'
import { corpusApi } from '../api/client'
import type { MetaInfo, GroupCounts } from '../api/types'
import { isDark, toggleTheme } from '../lib/theme'
import { authReady, currentUser, logout, openLogin } from '../lib/auth'
import AuthDialog from './AuthDialog.vue'

const router = useRouter()
const items = [
  { to: '/chat', label: '问答', icon: ChatDotRound, badge: false },
  { to: '/kb', label: '知识库', icon: Collection, badge: false },
  { to: '/quiz', label: '模拟面试', icon: Aim, badge: false },
  { to: '/records', label: '复盘', icon: DataAnalysis, badge: false },
]
const meta = ref<MetaInfo | null>(null)
const groupCounts = ref<GroupCounts | null>(null)
const isDarkNow = ref(isDark())

/**
 * 侧栏状态徽标,优先级:
 *   索引与向量模型不一致(含未索引)> mock 模式 > 模型已接入
 * 索引不一致是"检索结果不可信"级别的告警,必须比 mock 更醒目。
 */
const chip = computed(() => {
  const m = meta.value
  if (!m) return { cls: 'mock', text: '连接中…', tip: '正在读取服务状态' }
  if (!m.index?.compatible) {
    return {
      cls: 'bad',
      text: m.index?.indexed ? '索引待重建' : '未建索引',
      tip: m.index?.message || '请先 POST /api/admin/reindex',
    }
  }
  if (m.chatReal && m.embedReal) {
    return { cls: 'ok', text: '模型已接入', tip: `${m.chatModel} + ${m.embedModel}` }
  }
  return {
    cls: 'mock',
    text: 'Mock 模式',
    tip: '未配 API key,当前为占位回答;在项目根 .env 填 chat/embed 的 KEY 后重启',
  }
})

function toggle() {
  toggleTheme()
  isDarkNow.value = isDark()
}

/** 头像里的字:昵称首字,没昵称用邮箱首字母 */
const initial = computed(() => {
  const u = currentUser.value
  return (u?.nickname || u?.email || '?').charAt(0).toUpperCase()
})

function onLogout() {
  logout()
  ElMessage.success('已退出登录')
}

onMounted(async () => {
  try {
    meta.value = await corpusApi.meta()
    groupCounts.value = await corpusApi.groups()
  } catch {
    meta.value = null
  }
})
</script>

<style scoped>
.shell {
  display: flex;
  height: 100vh;
  background:
    radial-gradient(1200px 600px at 85% -10%, var(--accent-soft), transparent 60%),
    var(--bg);
}
.rail {
  width: 232px;
  flex: none;
  display: flex;
  flex-direction: column;
  border-right: 1px solid var(--border);
  background: color-mix(in srgb, var(--bg-panel) 86%, transparent);
  backdrop-filter: blur(8px);
  padding: 18px 12px 14px;
  gap: 4px;
}
.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 10px 18px;
  cursor: pointer;
}
.brand-mark {
  width: 38px; height: 38px; border-radius: 11px;
  display: grid; place-items: center;
  background: linear-gradient(135deg, var(--accent), var(--info));
  color: #062018;
  box-shadow: 0 6px 18px var(--accent-soft);
}
.brand-text { display: flex; flex-direction: column; line-height: 1.15; }
.brand-name { font-weight: 700; font-size: 17px; letter-spacing: -0.3px; }
.brand-sub { font-size: 11px; color: var(--text-dim); }

.nav { display: flex; flex-direction: column; gap: 2px; flex: 1; }
.nav-item {
  display: flex; align-items: center; gap: 10px;
  padding: 10px 12px; border-radius: var(--radius-sm);
  color: var(--text-dim); text-decoration: none;
  transition: background var(--t-fast) var(--ease), color var(--t-fast) var(--ease);
  font-weight: 500;
}
.nav-item:hover { background: var(--bg-hover); color: var(--text); }
.nav-item.active {
  background: var(--accent-soft);
  color: var(--accent);
}
.nav-item .badge {
  margin-left: auto; font-size: 11px; color: var(--text-faint);
}

.account { display: flex; align-items: center; gap: 10px; padding: 10px 6px; min-height: 52px; }
.avatar {
  width: 32px; height: 32px; flex: none; border-radius: 50%;
  display: grid; place-items: center; font-weight: 700; font-size: 14px;
  background: var(--accent-soft); color: var(--accent);
}
.account-text { display: flex; flex-direction: column; min-width: 0; line-height: 1.25; flex: 1; }
.account-name { font-size: 13px; font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.account-sub { font-size: 11px; color: var(--text-faint); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.account-out { flex: none; }
.login-btn {
  width: 100%; display: flex; align-items: center; justify-content: center; gap: 8px;
  padding: 9px 12px; border-radius: var(--radius-sm); border: 1px solid var(--border-strong);
  background: transparent; color: var(--text); font-weight: 500; cursor: pointer;
  transition: border-color var(--t-fast) var(--ease), color var(--t-fast) var(--ease);
}
.login-btn:hover { border-color: var(--accent); color: var(--accent); }

.rail-foot { display: flex; align-items: center; gap: 8px; padding: 8px 6px 0; border-top: 1px solid var(--border); }
.chip {
  display: inline-flex; align-items: center; gap: 6px;
  font-size: 11.5px; padding: 4px 8px; border-radius: 999px;
  background: var(--bg-soft); color: var(--text-dim);
}
.chip .dot { width: 7px; height: 7px; border-radius: 50%; }
.chip.ok .dot { background: var(--accent); box-shadow: 0 0 8px var(--accent); }
.chip.mock .dot { background: var(--warn); box-shadow: 0 0 8px var(--warn); }
.chip.bad { color: var(--danger); background: color-mix(in srgb, var(--danger) 12%, transparent); }
.chip.bad .dot { background: var(--danger); box-shadow: 0 0 8px var(--danger); }
.chip-dim { font-size: 11px; color: var(--text-faint); margin-left: auto; }
.ghost-btn {
  border: 0; background: transparent; color: var(--text-dim);
  padding: 6px; border-radius: 8px; display: grid; place-items: center;
}
.ghost-btn:hover { background: var(--bg-hover); color: var(--text); }

.content {
  flex: 1;
  min-width: 0;
  overflow-y: auto;
  padding: 22px 28px 40px;
}

/* ── 手机端(≤720px):顶栏 + 底部标签栏替代左侧 rail ── */
.mobile-topbar,
.mobile-tabbar { display: none; }

@media (max-width: 720px) {
  .shell {
    flex-direction: column;
    /* dvh 随手机地址栏收展自适应;老浏览器回退 100vh */
    height: 100vh;
    height: 100dvh;
  }
  .rail { display: none; }
  .content { padding: 14px 16px 24px; }

  .mobile-topbar {
    flex: none;
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 10px 14px;
    padding-top: calc(10px + env(safe-area-inset-top));
    border-bottom: 1px solid var(--border);
    background: color-mix(in srgb, var(--bg-panel) 86%, transparent);
    backdrop-filter: blur(8px);
  }
  .m-brand {
    display: flex;
    align-items: center;
    gap: 8px;
    cursor: pointer;
    min-width: 0;
  }
  .m-name { font-weight: 700; font-size: 16px; letter-spacing: -0.3px; }
  .m-actions {
    margin-left: auto;
    display: flex;
    align-items: center;
    gap: 6px;
    min-width: 0;
  }
  .m-account {
    font-size: 12.5px;
    color: var(--text-dim);
    max-width: 96px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .login-btn { width: auto; padding: 6px 12px; }

  .mobile-tabbar {
    flex: none;
    display: flex;
    padding: 4px 8px;
    padding-bottom: calc(4px + env(safe-area-inset-bottom));
    border-top: 1px solid var(--border);
    background: color-mix(in srgb, var(--bg-panel) 92%, transparent);
    backdrop-filter: blur(8px);
  }
  .tab {
    flex: 1;
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 2px;
    padding: 6px 0 5px;
    border-radius: 10px;
    text-decoration: none;
    color: var(--text-dim);
    font-size: 11px;
    font-weight: 500;
    transition: color var(--t-fast) var(--ease);
  }
  .tab.on { color: var(--accent); }
}
</style>
