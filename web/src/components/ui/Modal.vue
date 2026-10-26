<template>
  <Teleport to="body">
    <Transition name="modal" @after-enter="focusFirst" @after-leave="emit('closed')">
      <div v-if="open" class="modal-root" @mousedown.self="downOnBackdrop = true" @click.self="onBackdropClick">
        <div ref="panel" class="modal" role="dialog" aria-modal="true" :aria-label="title" tabindex="-1"
             :style="{ width: width + 'px' }" @mousedown="downOnBackdrop = false">
          <header v-if="title || $slots.header" class="modal-head">
            <slot name="header"><h2 class="modal-title">{{ title }}</h2></slot>
            <button class="btn btn-ghost btn-icon btn-sm modal-x" aria-label="关闭" @click="open = false">
              <X :size="16" />
            </button>
          </header>
          <div class="modal-body"><slot /></div>
          <footer v-if="$slots.footer" class="modal-foot"><slot name="footer" /></footer>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { X } from 'lucide-vue-next'
import { useOverlay } from '../../lib/overlay'

withDefaults(defineProps<{ title?: string; width?: number }>(), { width: 420 })
const emit = defineEmits<{ closed: [] }>()
const open = defineModel<boolean>('open', { required: true })

const panel = ref<HTMLElement | null>(null)
// 只有"按下和松开都在遮罩上"才算点遮罩:在弹框里拖选文字、松手落到遮罩上时不该关
const downOnBackdrop = ref(false)

useOverlay(open, () => { open.value = false })

function onBackdropClick() {
  if (downOnBackdrop.value) open.value = false
  downOnBackdrop.value = false
}

/** 打开后把焦点放进弹框:优先第一个输入框,没有就放在弹框本身,键盘 Tab 从这里开始 */
function focusFirst() {
  const el = panel.value?.querySelector<HTMLElement>('input, textarea, [data-autofocus]') ?? panel.value
  el?.focus({ preventScroll: true })
}
</script>

<style scoped>
.modal-root { position: fixed; inset: 0; z-index: 1000; display: grid; place-items: center; padding: 16px; background: var(--overlay); }
.modal { max-width: 100%; max-height: calc(100dvh - 32px); display: flex; flex-direction: column; background: var(--surface); border: 1px solid var(--line); border-radius: var(--r-xl); box-shadow: var(--shadow-pop); outline: none; }
.modal-head { display: flex; align-items: flex-start; gap: 12px; padding: 20px 20px 4px 24px; }
.modal-title { font-size: 17px; }
.modal-x { margin-left: auto; margin-top: -2px; }
.modal-body { padding: 12px 24px 24px; overflow-y: auto; }
.modal-foot { display: flex; justify-content: flex-end; gap: 8px; padding: 0 24px 20px; }

.modal-enter-active, .modal-leave-active { transition: opacity 180ms var(--ease); }
.modal-enter-active .modal, .modal-leave-active .modal { transition: transform 200ms var(--ease); }
.modal-enter-from, .modal-leave-to { opacity: 0; }
.modal-enter-from .modal, .modal-leave-to .modal { transform: translateY(8px) scale(0.98); }
</style>
