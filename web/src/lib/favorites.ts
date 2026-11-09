import { computed, onScopeDispose, ref, shallowRef, watch, type Ref } from 'vue'
import { favoritesApi } from '../api/client'
import type { FavoriteItem } from '../api/types'
import { authReady, currentUser, requireLogin } from './auth'
import { token } from './session'

/** 身份只用于本地判旧,从不传给后端,也不缓存任何用户的收藏。 */
function useIdentity() {
  return computed(() => authReady.value && currentUser.value && token.value
    ? `${currentUser.value.id}:${token.value}` : null)
}

function errorText(e: unknown, fallback: string): string {
  return e instanceof Error && e.message ? e.message : fallback
}

/** 收藏按钮的三态:未知(null)/已收藏/未收藏。失败绝不能伪装成“未收藏”。 */
export function useQuestionFavorite(chunkId: Readonly<Ref<number>>) {
  const identity = useIdentity()
  const favorited = ref<boolean | null>(null)
  const loading = ref(false)
  const saving = ref(false)
  const loginPending = ref(false)
  const error = ref('')
  let active = true
  let context = 0
  let request = 0
  let questionVersion = 0

  const busy = computed(() => !authReady.value || loading.value || saving.value || loginPending.value)
  const isCurrent = (ctx: number, req: number) => active && ctx === context && req === request

  async function load() {
    if (!identity.value || !active || saving.value) return
    const ctx = context
    const req = ++request
    const id = chunkId.value
    loading.value = true
    favorited.value = null
    error.value = ''
    try {
      const result = await favoritesApi.state(id)
      if (isCurrent(ctx, req)) favorited.value = result.favorited
    } catch (e) {
      if (isCurrent(ctx, req)) error.value = errorText(e, '收藏状态加载失败，请重试')
    } finally {
      if (isCurrent(ctx, req)) loading.value = false
    }
  }

  async function setFavorite(desired: boolean) {
    if (!identity.value || !active) return
    const ctx = context
    const req = ++request // 登录后自动发出的 GET 不能覆盖 PUT 的结果
    const id = chunkId.value
    loading.value = false
    saving.value = true
    error.value = ''
    try {
      const result = await (desired ? favoritesApi.add(id) : favoritesApi.remove(id))
      if (isCurrent(ctx, req)) favorited.value = result.favorited
    } catch (e) {
      if (isCurrent(ctx, req)) {
        // 网络失败时无法断定服务端是否已写入,下次先 GET 确认,不盲目反转。
        favorited.value = null
        error.value = errorText(e, '收藏操作未完成，请重试确认状态')
      }
    } finally {
      if (isCurrent(ctx, req)) saving.value = false
    }
  }

  async function toggle() {
    if (busy.value || !active) return
    if (!identity.value) {
      const version = questionVersion
      loginPending.value = true
      try {
        if (!(await requireLogin()) || !active || version !== questionVersion || !identity.value) return
        // 游客点击的是“收藏”,登录后做幂等添加,即使原来已收藏也不会误取消。
        await setFavorite(true)
      } finally {
        if (active) loginPending.value = false
      }
      return
    }
    if (favorited.value === null) await load()
    else await setFavorite(!favorited.value)
  }

  watch(chunkId, () => { questionVersion++ }, { flush: 'sync' })
  watch([identity, chunkId], () => {
    context++
    request++
    favorited.value = null
    loading.value = false
    saving.value = false
    error.value = ''
    if (identity.value) void load()
  }, { immediate: true, flush: 'sync' })
  onScopeDispose(() => { active = false; context++; request++; questionVersion++ })

  return { favorited, loading, saving, loginPending, error, busy, toggle }
}

/** 服务端分页;账号/筛选/请求版本共同隔离,离开页面后不再回写状态。 */
export function useFavoriteList() {
  const identity = useIdentity()
  const items = shallowRef<FavoriteItem[]>([])
  const page = ref(1)
  const size = 20
  const total = ref(0)
  const group = ref('')
  const loading = ref(false)
  const error = ref('')
  const actionError = ref('')
  const removingId = ref<number | null>(null)
  const pages = computed(() => Math.max(1, Math.ceil(total.value / size)))
  let active = true
  let context = 0
  let request = 0
  const isCurrent = (ctx: number, req: number) => active && ctx === context && req === request

  async function load() {
    if (!identity.value || !active) return
    const ctx = context
    const req = ++request
    const filter = group.value
    let targetPage = page.value
    loading.value = true
    error.value = ''
    items.value = []
    total.value = 0
    try {
      for (;;) {
        const result = await favoritesApi.list(targetPage, size, filter)
        if (!isCurrent(ctx, req)) return
        const last = Math.max(1, Math.ceil(result.total / size))
        // 取消末页最后一项,或其它标签页已删题时,退回仍然存在的最后一页。
        if (targetPage > last) {
          page.value = targetPage = last
          continue
        }
        items.value = result.items
        total.value = result.total
        page.value = result.page
        return
      }
    } catch (e) {
      if (isCurrent(ctx, req)) error.value = errorText(e, '收藏加载失败，请重试')
    } finally {
      if (isCurrent(ctx, req)) loading.value = false
    }
  }

  function selectGroup(value: string) {
    if (removingId.value !== null || group.value === value) return
    group.value = value
    page.value = 1
    actionError.value = ''
    void load()
  }

  function goPage(value: number) {
    if (loading.value || removingId.value !== null || value < 1 || value > pages.value || value === page.value) return
    page.value = value
    actionError.value = ''
    void load()
  }

  async function remove(item: FavoriteItem) {
    if (!identity.value || loading.value || removingId.value !== null || !active) return
    const ctx = context
    removingId.value = item.chunkId
    actionError.value = ''
    try {
      await favoritesApi.remove(item.chunkId)
      if (!active || ctx !== context) return
      // 服务端成功才移除,随后重取完整的一页,避免只剩 19 项或空的末页。
      items.value = items.value.filter((q) => q.chunkId !== item.chunkId)
      total.value = Math.max(0, total.value - 1)
      page.value = Math.min(page.value, pages.value)
      await load()
    } catch (e) {
      if (active && ctx === context) actionError.value = errorText(e, '取消收藏失败，请重试')
    } finally {
      if (active && ctx === context) removingId.value = null
    }
  }

  watch(identity, () => {
    context++
    request++
    items.value = []
    total.value = 0
    page.value = 1
    group.value = ''
    error.value = ''
    actionError.value = ''
    removingId.value = null
    loading.value = false
    if (identity.value) void load()
  }, { immediate: true, flush: 'sync' })
  onScopeDispose(() => { active = false; context++; request++ })

  return { items, page, size, total, pages, group, loading, error, actionError, removingId, load, selectGroup, goPage, remove }
}
