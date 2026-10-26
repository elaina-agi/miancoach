<template>
  <!-- 尺寸类名带 is- 前缀:根元素同时带着父组件的 scoped 属性,
       叫 hero / dock 会被问答页同名的布局样式命中(之前就是这样把输入框撑高的) -->
  <div class="composer" :class="[`is-${size}`, { focused }]" @click="focus">
    <textarea ref="ta" v-model="text" class="ta" rows="1" :placeholder="placeholder" :aria-label="placeholder"
              @input="resize" @keydown="onKeydown" @focus="focused = true" @blur="focused = false" />
    <button v-if="!streaming" class="send" :disabled="!text.trim() || disabled" aria-label="发送" title="发送（Enter）" @click.stop="emit('send')">
      <ArrowUp :size="18" :stroke-width="2.4" />
    </button>
    <button v-else class="send stop" aria-label="停止生成" title="停止生成" @click.stop="emit('stop')">
      <Square :size="12" fill="currentColor" />
    </button>
  </div>
</template>

<script setup lang="ts">
import { nextTick, onMounted, ref, watch } from 'vue'
import { ArrowUp, Square } from 'lucide-vue-next'

const props = withDefaults(defineProps<{
  streaming?: boolean
  disabled?: boolean
  placeholder?: string
  /** hero:空态居中的大输入框;dock:对话进行中贴底 */
  size?: 'hero' | 'dock'
  autofocus?: boolean
}>(), { placeholder: '问一道面试题，或者接着追问…', size: 'dock' })
const emit = defineEmits<{ send: []; stop: [] }>()
const text = defineModel<string>({ required: true })

const ta = ref<HTMLTextAreaElement | null>(null)
const focused = ref(false)
const MAX_H = 220

/** 随内容长高,超过上限后内部滚动 */
function resize() {
  const el = ta.value
  if (!el) return
  el.style.height = 'auto'
  el.style.height = Math.min(el.scrollHeight, MAX_H) + 'px'
}
// 外部改值(发送后清空、从知识库带着问题跳过来)也要重算高度
watch(text, () => void nextTick(resize))

function onKeydown(e: KeyboardEvent) {
  if (e.key !== 'Enter' || e.shiftKey) return
  // 中文输入法组字时按回车是"上屏英文",不是发送;不判断的话打拼音混英文会被提前发出去
  if (e.isComposing || e.keyCode === 229) return
  e.preventDefault()
  if (!props.streaming && !props.disabled && text.value.trim()) emit('send')
}

function focus() {
  ta.value?.focus()
}

defineExpose({ focus })

onMounted(() => {
  resize()
  // 手机上自动聚焦会立刻弹出键盘挡住半屏,只在有鼠标的设备上做
  if (props.autofocus && window.matchMedia('(pointer: fine)').matches) focus()
})
</script>

<style scoped>
/* 一行:输入框 + 发送键。多行时按钮贴底,跟着最后一行走 */
.composer { display: flex; align-items: flex-end; gap: 10px; padding: 9px 9px 9px 18px; background: var(--surface); border: 1px solid var(--line-strong); border-radius: var(--r-xl); box-shadow: var(--shadow-soft); cursor: text; transition: border-color var(--t) var(--ease), box-shadow var(--t) var(--ease); }
.composer.focused { border-color: color-mix(in srgb, var(--accent) 55%, var(--line-strong)); box-shadow: 0 0 0 3px var(--accent-soft), var(--shadow-soft); }
/* 单行高度和发送键一样是 34px,文字和按钮垂直居中对齐 */
.ta { flex: 1; min-width: 0; height: 34px; padding: 4px 0; border: 0; outline: none; resize: none; background: transparent; font-size: 15px; line-height: 26px; color: var(--ink); }
.ta::placeholder { color: var(--ink-3); }
.is-hero { padding: 11px 11px 11px 20px; }
.send { width: 34px; height: 34px; flex: none; display: grid; place-items: center; border: 0; border-radius: 50%; background: var(--accent); color: var(--accent-ink); cursor: pointer; transition: background var(--t) var(--ease), opacity var(--t) var(--ease); }
.send:hover:not(:disabled) { background: var(--accent-hover); }
.send:disabled { background: var(--fill-strong); color: var(--ink-3); cursor: not-allowed; }
.send.stop { background: var(--ink); color: var(--bg); }

/* 手机上 16px 字号,iOS 聚焦时不会自动放大页面 */
@media (max-width: 720px) {
  .composer, .is-hero { gap: 8px; padding: 6px 6px 6px 14px; }
  .ta { font-size: 16px; }
}
</style>
