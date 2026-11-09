<template>
  <div ref="scrollEl" class="page-scroll">
    <div class="favorites">
      <header class="intro">
        <div>
          <h1>我的收藏</h1>
          <p>把还想再读的题留在这里，复习时少找一步。</p>
        </div>
        <RouterLink to="/kb" class="btn btn-secondary"><BookOpen :size="15" aria-hidden="true" />浏览题库</RouterLink>
      </header>

      <section v-if="!authReady" class="empty" role="status">
        <LoaderCircle :size="26" class="loading-icon" aria-hidden="true" />
        <p>正在确认登录状态…</p>
      </section>
      <section v-else-if="!isLoggedIn" class="empty" aria-labelledby="favorites-login-title">
        <Bookmark :size="28" aria-hidden="true" />
        <h2 id="favorites-login-title">登录后，拥有自己的复习题单</h2>
        <p>在知识库题目页点「收藏题目」，就能在这里找回来。收藏仅自己可见。</p>
        <button class="btn btn-primary" :disabled="loginPending" @click="login">
          <LogIn :size="15" aria-hidden="true" />{{ loginPending ? '等待登录…' : '登录 / 注册' }}
        </button>
      </section>
      <template v-else>
        <section class="filters" aria-label="收藏筛选">
          <div class="filter-top">
            <span class="filter-label">按板块</span>
            <span class="filter-note">最近收藏在前</span>
          </div>
          <div class="groups" role="group" aria-label="按板块筛选收藏">
            <button type="button" class="g-chip" :class="{ on: !group }" :aria-pressed="!group"
                    :disabled="removingId !== null" @click="selectGroup('')">全部板块</button>
            <button v-for="g in groups" :key="g" type="button" class="g-chip" :class="{ on: group === g }"
                    :aria-pressed="group === g" :disabled="removingId !== null" @click="selectGroup(g)">{{ groupLabel(g) }}</button>
          </div>
          <p v-if="groupsFailed" class="group-error" role="status">
            板块选项加载失败，仍可查看全部收藏。
            <button class="link" :disabled="groupsLoading" @click="loadGroups">{{ groupsLoading ? '重试中…' : '重试' }}</button>
          </p>
        </section>

        <p v-if="actionError" class="action-error" role="alert"><CircleAlert :size="16" aria-hidden="true" />{{ actionError }}</p>
        <section class="results" :aria-busy="loading" aria-label="收藏题目">
          <div v-if="loading" class="loading-state" role="status">
            <span class="sr-only">正在加载收藏…</span>
            <div v-for="i in 5" :key="i" class="skeleton-card" aria-hidden="true">
              <div class="skeleton" style="width: 28%; height: 12px" />
              <div class="skeleton" :style="{ width: `${66 + i * 4}%`, height: '18px' }" />
              <div class="skeleton" style="width: 42%; height: 12px" />
            </div>
          </div>
          <div v-else-if="error" class="empty" role="alert">
            <CircleAlert :size="26" aria-hidden="true" />
            <h2>收藏暂时没有加载出来</h2>
            <p>{{ error }}</p>
            <button class="btn btn-secondary" @click="load"><RotateCcw :size="15" aria-hidden="true" />重新加载</button>
          </div>
          <div v-else-if="!items.length" class="empty">
            <Bookmark :size="28" aria-hidden="true" />
            <h2>{{ group ? `${groupLabel(group)}还没有收藏` : '还没有收藏的题目' }}</h2>
            <p>{{ group ? '换个板块看看，或者去题库收下想复习的题。' : '遇到暂时没掌握、想再读一遍的题，点一下收藏，下次就从这里开始。' }}</p>
            <div class="empty-actions">
              <button v-if="group" class="btn btn-secondary" @click="selectGroup('')">查看全部收藏</button>
              <RouterLink to="/kb" class="btn btn-primary"><BookOpen :size="15" aria-hidden="true" />去题库看看</RouterLink>
            </div>
          </div>
          <template v-else>
            <p class="result-count" role="status">{{ group ? groupLabel(group) : '全部板块' }} · <b class="num">{{ total }}</b> 道收藏</p>
            <ul class="favorite-list">
              <li v-for="item in items" :key="item.chunkId" class="favorite-card" :aria-busy="removingId === item.chunkId">
                <RouterLink :to="`/kb/${item.chunkId}`" class="question-link">
                  <span class="question-meta"><span class="tag">{{ groupLabel(item.group) }}</span><span>{{ item.category || item.title }}</span></span>
                  <h2>{{ item.question }}</h2>
                  <span class="question-bottom">
                    <time :datetime="item.createdAt">{{ savedDate(item.createdAt) }}收藏</time>
                    <span class="read-label">阅读题目<ArrowUpRight :size="14" aria-hidden="true" /></span>
                  </span>
                </RouterLink>
                <button type="button" class="btn btn-ghost remove-btn" :disabled="removingId !== null"
                        :aria-label="`取消收藏：${item.question}`" :title="`取消收藏：${item.question}`" @click="remove(item)">
                  <LoaderCircle v-if="removingId === item.chunkId" :size="16" class="spin" aria-hidden="true" />
                  <BookmarkMinus v-else :size="16" aria-hidden="true" />
                  <span>{{ removingId === item.chunkId ? '取消中…' : '取消收藏' }}</span>
                </button>
              </li>
            </ul>
            <nav v-if="pages > 1" class="pagination" aria-label="收藏分页">
              <button class="btn btn-secondary" :disabled="page <= 1 || removingId !== null" @click="changePage(page - 1)"><ChevronLeft :size="16" aria-hidden="true" />上一页</button>
              <span class="page-number num" role="status">第 {{ page }} / {{ pages }} 页</span>
              <button class="btn btn-secondary" :disabled="page >= pages || removingId !== null" @click="changePage(page + 1)">下一页<ChevronRight :size="16" aria-hidden="true" /></button>
            </nav>
          </template>
        </section>
      </template>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, shallowRef } from 'vue'
import { ArrowUpRight, Bookmark, BookmarkMinus, BookOpen, ChevronLeft, ChevronRight, CircleAlert, LoaderCircle, LogIn, RotateCcw } from 'lucide-vue-next'
import { corpusApi } from '../api/client'
import { authReady, isLoggedIn, requireLogin } from '../lib/auth'
import { groupLabel, sortGroups } from '../lib/catalog'
import { useFavoriteList } from '../lib/favorites'

const { items, page, total, pages, group, loading, error, actionError, removingId, load, selectGroup, goPage, remove } = useFavoriteList()
const scrollEl = ref<HTMLElement | null>(null)
const groupOptions = shallowRef<string[]>([])
const groupsLoading = ref(false)
const groupsFailed = ref(false)
const loginPending = ref(false)
let active = true
// 接口异常时仍保留当前列表包含的板块;公共选项没有用户数据,不缓存收藏结果。
const groups = computed(() => sortGroups([...new Set([...groupOptions.value, ...items.value.map((i) => i.group), ...(group.value ? [group.value] : [])])], (g) => g))

async function loadGroups() {
  if (groupsLoading.value) return
  groupsLoading.value = true
  try {
    const result = await corpusApi.groups()
    if (active) { groupOptions.value = Object.keys(result); groupsFailed.value = false }
  } catch {
    if (active) groupsFailed.value = true
  } finally {
    if (active) groupsLoading.value = false
  }
}

async function login() {
  if (loginPending.value) return
  loginPending.value = true
  try { await requireLogin() } finally { if (active) loginPending.value = false }
}

function changePage(value: number) {
  goPage(value)
  scrollEl.value?.scrollTo({ top: 0 })
}

const dateFormat = new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: 'short', day: 'numeric' })
function savedDate(value: string) {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '' : `${dateFormat.format(date)} `
}

onMounted(() => { void loadGroups() })
onBeforeUnmount(() => { active = false })
</script>

<style scoped>
.page-scroll { height: 100%; overflow-y: auto; }
.favorites { max-width: 960px; margin: 0 auto; padding: 40px 28px 64px; display: flex; flex-direction: column; gap: 20px; }
.intro { display: flex; align-items: center; justify-content: space-between; gap: 20px; margin-bottom: 8px; }
.intro h1 { font-size: 28px; letter-spacing: -0.02em; }
.intro p { margin-top: 8px; font-size: 15px; color: var(--ink-2); }
.intro > .btn { flex: none; }
.filters { padding: 18px 20px; border: 1px solid var(--line); border-radius: var(--r-lg); background: var(--surface); }
.filter-top { display: flex; justify-content: space-between; gap: 12px; margin-bottom: 12px; }
.filter-label { font-size: 13px; font-weight: 600; }
.filter-note { color: var(--ink-2); font-size: 12.5px; }
.groups { display: flex; flex-wrap: wrap; gap: 8px; }
.g-chip { min-height: 34px; padding: 4px 12px; border: 1px solid var(--line-strong); border-radius: 999px; background: transparent; color: var(--ink-2); font-size: 13px; cursor: pointer; transition: color var(--t) var(--ease), background var(--t) var(--ease), border-color var(--t) var(--ease); }
.g-chip:hover:not(:disabled) { background: var(--fill); color: var(--ink); }
.g-chip.on { background: var(--ink); border-color: var(--ink); color: var(--bg); }
.g-chip.on:hover:not(:disabled) { background: var(--ink); color: var(--bg); }
.g-chip:disabled { opacity: .5; cursor: not-allowed; }
.group-error { margin-top: 10px; color: var(--ink-2); font-size: 13px; }
.link { background: none; border: none; color: var(--accent); font: inherit; text-decoration: underline; padding: 4px; cursor: pointer; }
.link:disabled { cursor: wait; }
.empty { padding: 56px 24px; display: flex; flex-direction: column; align-items: center; gap: 12px; text-align: center; border: 1px dashed var(--line-strong); border-radius: var(--r-xl); color: var(--ink-2); }
.empty > svg { color: var(--ink-3); }
.empty h2 { font-size: 18px; color: var(--ink); }
.empty p { max-width: 480px; margin-bottom: 6px; font-size: 14px; overflow-wrap: anywhere; }
.empty-actions { display: flex; flex-wrap: wrap; justify-content: center; gap: 10px; }
.loading-icon { animation: spin .9s linear infinite; }
.loading-state { display: flex; flex-direction: column; gap: 12px; }
.skeleton-card { display: flex; flex-direction: column; gap: 16px; padding: 20px; border: 1px solid var(--line); border-radius: var(--r-lg); background: var(--surface); }
.action-error { display: flex; align-items: flex-start; gap: 8px; padding: 12px 16px; background: var(--bad-soft); border-radius: var(--r); color: var(--bad); overflow-wrap: anywhere; }
.action-error svg { margin-top: 3px; }
.result-count { color: var(--ink-2); font-size: 13px; margin-bottom: 12px; }
.result-count b { color: var(--ink); font-weight: 600; }
.favorite-list { display: flex; flex-direction: column; gap: 12px; padding: 0; margin: 0; list-style: none; }
.favorite-card { display: flex; align-items: center; min-width: 0; border: 1px solid var(--line); border-radius: var(--r-lg); background: var(--surface); transition: border-color var(--t) var(--ease); }
.favorite-card:hover { border-color: var(--line-strong); }
.question-link { display: flex; flex: 1; min-width: 0; flex-direction: column; gap: 10px; padding: 18px 20px; border-radius: var(--r-lg); cursor: pointer; transition: background var(--t) var(--ease); }
.question-link:hover { background: var(--fill); }
.question-link h2 { font-size: 16px; line-height: 1.65; overflow-wrap: anywhere; }
.question-meta { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; color: var(--ink-2); font-size: 12.5px; overflow-wrap: anywhere; }
.question-bottom { display: flex; align-items: center; flex-wrap: wrap; gap: 8px 18px; color: var(--ink-2); font-size: 12px; }
.read-label { display: inline-flex; align-items: center; gap: 3px; color: var(--accent); font-weight: 500; }
.remove-btn { flex: none; min-height: 38px; margin: 12px 16px 12px 0; font-size: 13px; }
.pagination { display: flex; align-items: center; justify-content: center; gap: 18px; margin-top: 24px; }
.page-number { font-size: 13px; color: var(--ink-2); white-space: nowrap; }
@media (max-width: 720px) {
  .favorites { padding: 22px 16px 40px; gap: 16px; }
  .intro { align-items: flex-start; flex-wrap: wrap; gap: 14px; }
  .intro h1 { font-size: 22px; }
  .intro p { font-size: 14px; }
  .filters { padding: 16px; }
  .g-chip { min-height: 40px; }
  .empty { padding: 40px 18px; }
  .favorite-card { flex-direction: column; align-items: stretch; }
  .question-link { padding: 16px; }
  .remove-btn { align-self: flex-end; min-height: 40px; margin: 0 10px 8px; }
  .pagination { gap: 10px; }
  .pagination .btn { min-height: 40px; padding: 0 10px; }
}
</style>
