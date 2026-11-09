// Node 原生测试 + 项目已有 TypeScript/Vue,不引入测试框架或浏览器依赖。
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import ts from 'typescript'
import * as vue from 'vue'

function loadTs(path, modules) {
  const source = readFileSync(new URL(path, import.meta.url), 'utf8')
  const { outputText } = ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  })
  const exports = {}
  new Function('require', 'exports', outputText)((name) => {
    if (!(name in modules)) throw new Error(`Unexpected import: ${name}`)
    return modules[name]
  }, exports)
  return exports
}

function deferred() {
  let resolve, reject
  const promise = new Promise((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}
async function flush() {
  await Promise.resolve()
  await vue.nextTick()
  await Promise.resolve()
}
const user = (id) => ({ id, email: `${id}@example.com`, role: 'USER' })
const item = (chunkId, group = 'java') => ({ chunkId, group, title: 'Java 基础', category: null, question: `题目 ${chunkId}`, createdAt: '2026-11-09T00:00:00Z' })
const page = (items, total = items.length, value = 1) => ({ items, total, page: value, size: 20 })

function fixture(t, { guest = false } = {}) {
  const authReady = vue.ref(true)
  const currentUser = vue.ref(guest ? null : user('a'))
  const token = vue.ref(guest ? null : 'token-a')
  const login = deferred()
  const calls = []
  const favoritesApi = Object.fromEntries(['state', 'add', 'remove', 'list'].map((name) => [name, (...args) => {
    const pending = deferred()
    calls.push({ name, args, ...pending })
    return pending.promise
  }]))
  const mod = loadTs('../src/lib/favorites.ts', {
    vue,
    '../api/client': { favoritesApi },
    './auth': { authReady, currentUser, requireLogin: () => login.promise },
    './session': { token },
  })
  const scope = vue.effectScope()
  t.after(() => scope.stop())
  return {
    ...mod, authReady, currentUser, token, login, calls, scope,
    button(id = vue.ref(1)) { return scope.run(() => mod.useQuestionFavorite(id)) },
    list() { return scope.run(() => mod.useFavoriteList()) },
  }
}

test('收藏按钮从未知态读取,不重复提交,失败不显示成未收藏', async (t) => {
  const f = fixture(t)
  const state = f.button()
  assert.equal(state.favorited.value, null)
  assert.equal(state.busy.value, true)
  f.calls[0].resolve({ favorited: false })
  await flush()
  const save = state.toggle()
  void state.toggle()
  assert.equal(f.calls.length, 2)
  assert.equal(f.calls[1].name, 'add')
  assert.equal(state.saving.value, true)
  f.calls[1].reject(new Error('网络中断'))
  await save
  assert.equal(state.favorited.value, null)
  assert.equal(state.error.value, '网络中断')
  const retry = state.toggle()
  assert.equal(f.calls[2].name, 'state')
  f.calls[2].resolve({ favorited: true })
  await retry
  assert.equal(state.favorited.value, true)
})

test('快速切题 A→B→A,旧 A 响应不能覆盖新 A 状态', async (t) => {
  const f = fixture(t)
  const id = vue.ref(1)
  const state = f.button(id)
  id.value = 2
  id.value = 1
  assert.deepEqual(f.calls.map((c) => c.args), [[1], [2], [1]])
  f.calls[2].resolve({ favorited: true })
  await flush()
  f.calls[0].resolve({ favorited: false })
  f.calls[1].reject(new Error('旧题失败'))
  await flush()
  assert.equal(state.favorited.value, true)
  assert.equal(state.error.value, '')
})

test('切换账号立即清除收藏,旧用户的读取和写入均不回写', async (t) => {
  const f = fixture(t)
  const state = f.button()
  f.calls[0].resolve({ favorited: false })
  await flush()
  const save = state.toggle()
  f.currentUser.value = user('b')
  f.token.value = 'token-b'
  assert.equal(state.favorited.value, null)
  assert.equal(state.loading.value, true)
  f.calls.at(-1).resolve({ favorited: false })
  await flush()
  f.calls[1].resolve({ favorited: true })
  await save
  assert.equal(state.favorited.value, false)
  f.currentUser.value = null
  f.token.value = null
  assert.equal(state.favorited.value, null)
  assert.equal(state.error.value, '')
})

test('游客登录后继续幂等收藏,登录触发的慢 GET 不覆盖 PUT', async (t) => {
  const f = fixture(t, { guest: true })
  const state = f.button()
  const action = state.toggle()
  assert.equal(state.loginPending.value, true)
  assert.equal(f.calls.length, 0)
  f.token.value = 'token-a'
  f.currentUser.value = user('a')
  f.login.resolve(true)
  await flush()
  assert.deepEqual(f.calls.map((c) => c.name), ['state', 'add'])
  f.calls[1].resolve({ favorited: true })
  await action
  f.calls[0].resolve({ favorited: false })
  await flush()
  assert.equal(state.favorited.value, true)
  assert.equal(state.busy.value, false)
})

test('游客登录等待时切题或组件卸载,不收藏错误的题目', async (t) => {
  const f = fixture(t, { guest: true })
  const id = vue.ref(1)
  const state = f.button(id)
  const action = state.toggle()
  id.value = 2
  f.token.value = 'token-a'
  f.currentUser.value = user('a')
  f.login.resolve(true)
  await action
  assert.ok(f.calls.every((c) => c.name !== 'add'))
  f.scope.stop()
  f.calls[0].resolve({ favorited: true })
  await flush()
  assert.equal(state.favorited.value, null)
})

test('收藏列表使用后端分页,快速筛选仅接受最后一次请求', async (t) => {
  const f = fixture(t)
  const state = f.list()
  assert.deepEqual(f.calls[0].args, [1, 20, ''])
  f.calls[0].resolve(page([item(1)], 41))
  await flush()
  state.goPage(2)
  assert.deepEqual(f.calls[1].args, [2, 20, ''])
  state.selectGroup('java')
  state.selectGroup('database')
  assert.deepEqual(f.calls[3].args, [1, 20, 'database'])
  f.calls[3].resolve(page([item(9, 'database')]))
  f.calls[1].resolve(page([item(2)], 41, 2))
  f.calls[2].reject(new Error('旧筛选失败'))
  await flush()
  assert.equal(state.group.value, 'database')
  assert.equal(state.page.value, 1)
  assert.deepEqual(state.items.value.map((q) => q.chunkId), [9])
  assert.equal(state.error.value, '')
})

test('取消末页最后一项会退回上一页并重新拉取,重复点击不重复删除', async (t) => {
  const f = fixture(t)
  const state = f.list()
  f.calls[0].resolve(page([item(1)], 21))
  await flush()
  state.goPage(2)
  f.calls[1].resolve(page([item(21)], 21, 2))
  await flush()
  const remove = state.remove(item(21))
  void state.remove(item(21))
  state.selectGroup('database')
  assert.equal(f.calls.length, 3)
  assert.equal(f.calls[2].name, 'remove')
  assert.equal(state.group.value, '')
  f.calls[2].resolve({ favorited: false })
  await flush()
  assert.deepEqual(f.calls[3].args, [1, 20, ''])
  f.calls[3].resolve(page([item(1)], 20))
  await remove
  assert.equal(state.page.value, 1)
  assert.equal(state.total.value, 20)
  assert.equal(state.removingId.value, null)
})

test('列表请求的总数减少时自动纠正页码,失败保留错误而不是空态', async (t) => {
  const f = fixture(t)
  const state = f.list()
  f.calls[0].resolve(page([item(1)], 61))
  await flush()
  state.goPage(4)
  f.calls[1].resolve(page([], 20, 4))
  await flush()
  assert.deepEqual(f.calls[2].args, [1, 20, ''])
  f.calls[2].reject(new Error('服务不可用'))
  await flush()
  assert.equal(state.page.value, 1)
  assert.equal(state.error.value, '服务不可用')
  assert.equal(state.loading.value, false)
})

test('列表退出清空数据,旧列表与取消操作不能污染新用户', async (t) => {
  const f = fixture(t)
  const state = f.list()
  f.calls[0].resolve(page([item(1)]))
  await flush()
  const remove = state.remove(item(1))
  f.token.value = null
  f.currentUser.value = null
  assert.deepEqual(state.items.value, [])
  assert.equal(state.total.value, 0)
  assert.equal(state.removingId.value, null)
  f.token.value = 'token-b'
  f.currentUser.value = user('b')
  f.calls.at(-1).resolve(page([item(2)]))
  await flush()
  f.calls[1].reject(new Error('旧操作失败'))
  await remove
  assert.deepEqual(state.items.value.map((q) => q.chunkId), [2])
  assert.equal(state.actionError.value, '')
})

test('取消失败保留条目供重试,不会当成删除成功', async (t) => {
  const f = fixture(t)
  const state = f.list()
  f.calls[0].resolve(page([item(1)]))
  await flush()
  const remove = state.remove(item(1))
  f.calls[1].reject(new Error('删除失败'))
  await remove
  assert.equal(state.items.value.length, 1)
  assert.equal(state.total.value, 1)
  assert.equal(state.actionError.value, '删除失败')
  assert.equal(state.removingId.value, null)
})

test('收藏 API 契约:分页参数、无 userId、PUT 无 body,旧 401 不清新 token', async (t) => {
  const token = vue.ref('old-token')
  let cleared = 0
  const calls = []
  const oldFetch = globalThis.fetch
  t.after(() => { globalThis.fetch = oldFetch })
  globalThis.fetch = (path, init) => {
    const pending = deferred()
    calls.push({ path, init, ...pending })
    return pending.promise
  }
  const { favoritesApi } = loadTs('../src/api/client.ts', {
    '../lib/session': { token, clearSession: () => { cleared++; token.value = null } },
  })
  const response = (body, status = 200) => new Response(JSON.stringify(body), { status })
  const list = favoritesApi.list(2, 20, 'java')
  assert.equal(calls[0].path, '/api/favorites?page=2&size=20&group=java')
  assert.equal(calls[0].init.headers.Authorization, 'Bearer old-token')
  calls[0].resolve(response(page([], 0, 2)))
  await list
  const add = favoritesApi.add(42)
  assert.equal(calls[1].init.method, 'PUT')
  assert.equal(calls[1].init.body, undefined)
  calls[1].resolve(response({ favorited: true }))
  await add
  const state = favoritesApi.state(42)
  token.value = 'new-token'
  calls[2].resolve(response({ error: '已过期' }, 401))
  await assert.rejects(state, /已过期/)
  assert.equal(cleared, 0)
  assert.equal(token.value, 'new-token')
  const remove = favoritesApi.remove(42)
  assert.equal(calls[3].init.method, 'DELETE')
  calls[3].resolve(response({ error: '已过期' }, 401))
  await assert.rejects(remove, /已过期/)
  assert.equal(cleared, 1)
  assert.equal(token.value, null)
})
