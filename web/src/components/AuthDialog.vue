<template>
  <Modal v-model:open="dialogVisible" :width="400" @closed="onClosed">
    <template #header>
      <div class="head">
        <BrandMark :size="30" />
        <h2>{{ dialogTab === 'login' ? '登录 MianCoach' : '注册账号' }}</h2>
        <p>登录后，问答历史和模拟面试记录会跟着账号走</p>
      </div>
    </template>

    <div class="seg" role="tablist">
      <button role="tab" :aria-selected="dialogTab === 'login'" :class="{ on: dialogTab === 'login' }" @click="dialogTab = 'login'">登录</button>
      <button role="tab" :aria-selected="dialogTab === 'register'" :class="{ on: dialogTab === 'register' }" @click="dialogTab = 'register'">注册</button>
    </div>

    <!-- 登录 -->
    <form v-if="dialogTab === 'login'" class="form" novalidate @submit.prevent="onLogin">
      <label class="field">
        <span class="label">邮箱</span>
        <input v-model.trim="loginForm.email" class="input" :class="{ invalid: errs.loginEmail }" type="email"
               autocomplete="username" placeholder="you@example.com" @blur="checkLoginEmail" />
        <span v-if="errs.loginEmail" class="err">{{ errs.loginEmail }}</span>
      </label>
      <label class="field">
        <span class="label">密码</span>
        <span class="pw">
          <input v-model="loginForm.password" class="input" :class="{ invalid: errs.loginPw }" :type="showPw ? 'text' : 'password'"
                 autocomplete="current-password" />
          <button type="button" class="pw-eye" :aria-label="showPw ? '隐藏密码' : '显示密码'" @click="showPw = !showPw">
            <EyeOff v-if="showPw" :size="16" /><Eye v-else :size="16" />
          </button>
        </span>
        <span v-if="errs.loginPw" class="err">{{ errs.loginPw }}</span>
      </label>
      <button class="btn btn-primary btn-lg submit" type="submit" :disabled="submitting">
        <LoaderCircle v-if="submitting" :size="16" class="spin" />登录
      </button>
      <p class="switch">还没有账号？<a href="#" @click.prevent="dialogTab = 'register'">注册一个</a></p>
    </form>

    <!-- 注册 -->
    <form v-else class="form" novalidate @submit.prevent="onRegister">
      <label class="field">
        <span class="label">邮箱</span>
        <input v-model.trim="regForm.email" class="input" :class="{ invalid: errs.regEmail }" type="email"
               autocomplete="email" placeholder="you@example.com" @blur="checkRegEmail" />
        <span v-if="errs.regEmail" class="err">{{ errs.regEmail }}</span>
      </label>
      <label class="field">
        <span class="label">验证码</span>
        <span class="code-row">
          <input v-model.trim="regForm.code" class="input" :class="{ invalid: errs.code }" maxlength="6" inputmode="numeric"
                 autocomplete="one-time-code" placeholder="6 位数字" />
          <button type="button" class="btn btn-secondary code-btn" :disabled="countdown > 0 || sending" @click="onSendCode">
            <LoaderCircle v-if="sending" :size="15" class="spin" />
            {{ countdown > 0 ? `${countdown} 秒后重发` : '获取验证码' }}
          </button>
        </span>
        <span v-if="errs.code" class="err">{{ errs.code }}</span>
      </label>
      <label class="field">
        <span class="label">密码</span>
        <span class="pw">
          <input v-model="regForm.password" class="input" :class="{ invalid: errs.regPw }" :type="showPw ? 'text' : 'password'"
                 autocomplete="new-password" placeholder="至少 8 位" />
          <button type="button" class="pw-eye" :aria-label="showPw ? '隐藏密码' : '显示密码'" @click="showPw = !showPw">
            <EyeOff v-if="showPw" :size="16" /><Eye v-else :size="16" />
          </button>
        </span>
        <span v-if="errs.regPw" class="err">{{ errs.regPw }}</span>
      </label>
      <label class="field">
        <span class="label">昵称 <em>可选</em></span>
        <input v-model="regForm.nickname" class="input" maxlength="20" placeholder="显示用，不填也行" />
      </label>
      <button class="btn btn-primary btn-lg submit" type="submit" :disabled="submitting">
        <LoaderCircle v-if="submitting" :size="16" class="spin" />注册并登录
      </button>
      <p class="switch">已有账号？<a href="#" @click.prevent="dialogTab = 'login'">直接登录</a></p>
      <p v-if="isDev" class="dev">开发模式：发信端为 mock 时，验证码在 logs/server.log 里搜 [mock mail]</p>
    </form>
  </Modal>
</template>

<script setup lang="ts">
import { onBeforeUnmount, reactive, ref, watch } from 'vue'
import { Eye, EyeOff, LoaderCircle } from 'lucide-vue-next'
import Modal from './ui/Modal.vue'
import BrandMark from './BrandMark.vue'
import { ApiError, authApi } from '../api/client'
import { cancelLogin, dialogTab, dialogVisible, finishLogin } from '../lib/auth'
import { toast } from '../lib/toast'

const isDev = import.meta.env.DEV

const loginForm = reactive({ email: '', password: '' })
const regForm = reactive({ email: '', code: '', password: '', nickname: '' })
const submitting = ref(false)
const showPw = ref(false)

// 规则与后端 AuthController / AuthService 一致;前端校验只为即时提示,后端照样会校验
const errs = reactive({ loginEmail: '', loginPw: '', regEmail: '', code: '', regPw: '' })
const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/

function emailError(v: string): string {
  if (!v) return '请输入邮箱'
  return EMAIL_RE.test(v) ? '' : '邮箱格式不正确'
}
const checkLoginEmail = () => { if (loginForm.email) errs.loginEmail = emailError(loginForm.email) }
const checkRegEmail = () => { if (regForm.email) errs.regEmail = emailError(regForm.email) }

// 用户一改输入就把对应的错误清掉,不用等到下次提交
watch(() => loginForm.email, () => { errs.loginEmail = '' })
watch(() => loginForm.password, () => { errs.loginPw = '' })
watch(() => regForm.email, () => { errs.regEmail = '' })
watch(() => regForm.code, () => { errs.code = '' })
watch(() => regForm.password, () => { errs.regPw = '' })
// 切换登录/注册时把邮箱带过去,常见情况是"登录发现没账号 → 去注册"
watch(dialogTab, (tab) => {
  if (tab === 'register' && !regForm.email) regForm.email = loginForm.email
  if (tab === 'login' && !loginForm.email) loginForm.email = regForm.email
})

// ── 验证码倒计时 ──
const countdown = ref(0)
const sending = ref(false)
let timer: number | undefined

function startCountdown(secs: number) {
  countdown.value = secs
  clearInterval(timer)
  timer = window.setInterval(() => {
    if (--countdown.value <= 0) clearInterval(timer)
  }, 1000)
}

async function onSendCode() {
  // 只校验邮箱这一项,其它还没填不该报错
  errs.regEmail = emailError(regForm.email)
  if (errs.regEmail) return
  sending.value = true
  try {
    await authApi.sendCode(regForm.email)
    toast.success('验证码已发送，请查收邮件')
    startCountdown(60)
  } catch (e) {
    // 429:后端提示里带着还要等几秒,直接从提示里取,按钮倒计时和后端保持一致
    if (e instanceof ApiError && e.status === 429) {
      const secs = Number(/(\d+)\s*秒/.exec(e.message)?.[1])
      if (secs > 0) startCountdown(secs)
    }
    toast.error((e as Error).message)
  } finally {
    sending.value = false
  }
}

onBeforeUnmount(() => clearInterval(timer))

// ── 提交 ──

async function onLogin() {
  errs.loginEmail = emailError(loginForm.email)
  errs.loginPw = loginForm.password ? '' : '请输入密码'
  if (errs.loginEmail || errs.loginPw) return
  submitting.value = true
  try {
    const out = await authApi.login(loginForm.email, loginForm.password)
    finishLogin(out)
    toast.success('登录成功')
  } catch (e) {
    toast.error((e as Error).message)
  } finally {
    submitting.value = false
  }
}

async function onRegister() {
  errs.regEmail = emailError(regForm.email)
  errs.code = /^\d{6}$/.test(regForm.code) ? '' : (regForm.code ? '验证码是 6 位数字' : '请输入验证码')
  errs.regPw = regForm.password.length >= 8 ? '' : (regForm.password ? '密码至少 8 位' : '请输入密码')
  if (errs.regEmail || errs.code || errs.regPw) return
  submitting.value = true
  try {
    const out = await authApi.register(regForm.email, regForm.code, regForm.password,
      regForm.nickname.trim() || undefined)
    finishLogin(out)
    toast.success(`注册成功，欢迎 ${out.user.nickname ?? out.user.email}`)
  } catch (e) {
    toast.error((e as Error).message)
  } finally {
    submitting.value = false
  }
}

/** 关闭后清掉密码和验证码(邮箱保留,下次打开不用重填);同时通知 requireLogin 的等待方"没登录" */
function onClosed() {
  loginForm.password = ''
  regForm.password = ''
  regForm.code = ''
  showPw.value = false
  Object.assign(errs, { loginEmail: '', loginPw: '', regEmail: '', code: '', regPw: '' })
  cancelLogin()
}
</script>

<style scoped>
.head { display: flex; flex-direction: column; gap: 6px; padding-top: 4px; }
.head h2 { margin-top: 8px; font-size: 19px; }
.head p { font-size: 13px; color: var(--ink-3); }

.seg { display: grid; grid-template-columns: 1fr 1fr; padding: 3px; margin: 8px 0 18px; border-radius: var(--r); background: var(--fill); }
.seg button { height: 32px; border: 0; border-radius: 8px; background: transparent; color: var(--ink-2); font-weight: 500; cursor: pointer; transition: background var(--t) var(--ease), color var(--t) var(--ease); }
.seg button.on { background: var(--surface); color: var(--ink); font-weight: 600; box-shadow: 0 1px 2px rgba(0, 0, 0, 0.08); }

.form { display: flex; flex-direction: column; gap: 14px; }
.field { display: flex; flex-direction: column; gap: 6px; }
.label { font-size: 13px; font-weight: 500; color: var(--ink-2); }
.label em { font-style: normal; color: var(--ink-3); font-weight: 400; margin-left: 4px; }
.err { font-size: 12.5px; color: var(--bad); }
.pw { position: relative; display: block; }
.pw .input { padding-right: 40px; }
.pw-eye { position: absolute; right: 4px; top: 50%; transform: translateY(-50%); width: 32px; height: 32px; display: grid; place-items: center; border: 0; border-radius: var(--r-sm); background: transparent; color: var(--ink-3); cursor: pointer; }
.pw-eye:hover { color: var(--ink); background: var(--fill); }
.code-row { display: flex; gap: 8px; }
.code-btn { flex: none; height: 38px; width: 118px; }
.submit { width: 100%; margin-top: 6px; }
.switch { text-align: center; font-size: 13px; color: var(--ink-3); }
.switch a { color: var(--accent); font-weight: 500; margin-left: 2px; }
.switch a:hover { text-decoration: underline; }
.dev { font-size: 12px; color: var(--ink-3); text-align: center; padding: 8px 10px; border-radius: var(--r); background: var(--fill); }
</style>
