<template>
  <el-dialog v-model="dialogVisible" width="400px" align-center class="auth-dialog"
             :show-close="true" @closed="onClosed">
    <template #header>
      <div class="auth-head">
        <span class="auth-title">{{ dialogTab === 'login' ? '登录' : '注册' }}</span>
        <span class="auth-sub">登录后可保存问答历史、模拟面试记录与收藏</span>
      </div>
    </template>

    <el-tabs v-model="dialogTab" stretch class="auth-tabs">
      <!-- 登录 -->
      <el-tab-pane label="登录" name="login">
        <el-form ref="loginRef" :model="loginForm" :rules="loginRules" label-position="top"
                 @submit.prevent="onLogin">
          <el-form-item label="邮箱" prop="email">
            <el-input v-model="loginForm.email" autocomplete="username" placeholder="you@example.com" />
          </el-form-item>
          <el-form-item label="密码" prop="password">
            <!-- 回车提交靠 form 的原生 submit,不再单独绑 keyup.enter,否则会提交两次 -->
            <el-input v-model="loginForm.password" type="password" show-password
                      autocomplete="current-password" />
          </el-form-item>
          <el-button type="primary" class="auth-submit" :loading="submitting" native-type="submit">
            登录
          </el-button>
        </el-form>
        <p class="auth-switch">还没有账号?<a @click="dialogTab = 'register'">去注册</a></p>
      </el-tab-pane>

      <!-- 注册 -->
      <el-tab-pane label="注册" name="register">
        <el-form ref="regRef" :model="regForm" :rules="regRules" label-position="top"
                 @submit.prevent="onRegister">
          <el-form-item label="邮箱" prop="email">
            <el-input v-model="regForm.email" autocomplete="email" placeholder="you@example.com" />
          </el-form-item>
          <el-form-item label="验证码" prop="code">
            <div class="code-row">
              <el-input v-model="regForm.code" maxlength="6" inputmode="numeric"
                        autocomplete="one-time-code" placeholder="6 位数字" />
              <el-button :disabled="countdown > 0" :loading="sending" @click="onSendCode">
                {{ countdown > 0 ? `${countdown} 秒后重发` : '获取验证码' }}
              </el-button>
            </div>
          </el-form-item>
          <el-form-item label="密码" prop="password">
            <el-input v-model="regForm.password" type="password" show-password
                      autocomplete="new-password" placeholder="至少 8 位" />
          </el-form-item>
          <el-form-item label="昵称(可选)" prop="nickname">
            <el-input v-model="regForm.nickname" maxlength="20" placeholder="显示用,不填也行" />
          </el-form-item>
          <el-button type="primary" class="auth-submit" :loading="submitting" native-type="submit">
            注册并登录
          </el-button>
        </el-form>
        <p class="auth-switch">已有账号?<a @click="dialogTab = 'login'">去登录</a></p>
        <p v-if="isDev" class="auth-dev">开发模式:发信端为 mock 时,验证码在 logs/server.log 里搜 [mock mail]</p>
      </el-tab-pane>
    </el-tabs>
  </el-dialog>
</template>

<script setup lang="ts">
import { onBeforeUnmount, reactive, ref } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { ApiError, authApi } from '../api/client'
import { cancelLogin, dialogTab, dialogVisible, finishLogin } from '../lib/auth'

const isDev = import.meta.env.DEV

const loginRef = ref<FormInstance>()
const regRef = ref<FormInstance>()
const loginForm = reactive({ email: '', password: '' })
const regForm = reactive({ email: '', code: '', password: '', nickname: '' })
const submitting = ref(false)

// 规则与后端 AuthController / AuthService 一致;前端校验只为即时提示,后端照样会校验
const email = [
  { required: true, message: '请输入邮箱', trigger: 'blur' },
  { type: 'email' as const, message: '邮箱格式不正确', trigger: 'blur' },
]
const loginRules: FormRules = {
  email,
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}
const regRules: FormRules = {
  email,
  code: [
    { required: true, message: '请输入验证码', trigger: 'blur' },
    { pattern: /^\d{6}$/, message: '验证码是 6 位数字', trigger: 'blur' },
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 8, message: '密码至少 8 位', trigger: 'blur' },
  ],
}

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
  try { await regRef.value?.validateField('email') } catch { return }
  sending.value = true
  try {
    await authApi.sendCode(regForm.email)
    ElMessage.success('验证码已发送,请查收邮件')
    startCountdown(60)
  } catch (e) {
    // 429:后端提示里带着还要等几秒,直接从提示里取,按钮倒计时和后端保持一致
    if (e instanceof ApiError && e.status === 429) {
      const secs = Number(/(\d+)\s*秒/.exec(e.message)?.[1])
      if (secs > 0) startCountdown(secs)
    }
    ElMessage.error((e as Error).message)
  } finally {
    sending.value = false
  }
}

onBeforeUnmount(() => clearInterval(timer))

// ── 提交 ──

async function onLogin() {
  if (!(await loginRef.value?.validate().catch(() => false))) return
  submitting.value = true
  try {
    const out = await authApi.login(loginForm.email, loginForm.password)
    finishLogin(out)
    ElMessage.success('登录成功')
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    submitting.value = false
  }
}

async function onRegister() {
  if (!(await regRef.value?.validate().catch(() => false))) return
  submitting.value = true
  try {
    const out = await authApi.register(regForm.email, regForm.code, regForm.password,
      regForm.nickname.trim() || undefined)
    finishLogin(out)
    ElMessage.success(`注册成功,欢迎 ${out.user.nickname ?? out.user.email}`)
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    submitting.value = false
  }
}

/** 关闭后清掉密码和验证码(邮箱保留,下次打开不用重填);同时通知 requireLogin 的等待方"没登录" */
function onClosed() {
  loginForm.password = ''
  regForm.password = ''
  regForm.code = ''
  loginRef.value?.clearValidate()
  regRef.value?.clearValidate()
  cancelLogin()
}
</script>

<style scoped>
.auth-head { display: flex; flex-direction: column; gap: 4px; }
.auth-title { font-size: 18px; font-weight: 700; letter-spacing: -0.2px; }
.auth-sub { font-size: 12px; color: var(--text-faint); }
.auth-tabs :deep(.el-tabs__header) { margin-bottom: 18px; }
.code-row { display: flex; gap: 8px; width: 100%; }
.code-row .el-button { flex: none; width: 112px; }
.auth-submit { width: 100%; margin-top: 6px; }
.auth-switch { margin: 14px 0 0; text-align: center; font-size: 12.5px; color: var(--text-dim); }
.auth-switch a { color: var(--accent); cursor: pointer; margin-left: 2px; }
.auth-switch a:hover { text-decoration: underline; }
.auth-dev { margin: 10px 0 0; font-size: 11px; color: var(--text-faint); text-align: center; }
</style>
