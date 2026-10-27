<template>
  <Teleport to="body">
    <Transition :name="`sheet-${side}`">
      <div v-if="open" class="sheet-root" @click.self="open = false">
        <aside class="sheet" :class="side" role="dialog" aria-modal="true" :aria-label="label"
               :style="side === 'bottom' ? undefined : { width: `min(${width}px, 92vw)` }">
          <slot />
        </aside>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup lang="ts">
import { useOverlay } from '../../lib/overlay'

// 侧滑面板:窄屏下的历史会话、来源原文都放这里。内容自己负责头部和滚动
withDefaults(defineProps<{ side?: 'left' | 'right' | 'bottom'; width?: number; label?: string }>(),
  { side: 'right', width: 420, label: undefined })
const open = defineModel<boolean>('open', { required: true })

useOverlay(open, () => { open.value = false })
</script>

<style scoped>
.sheet-root { position: fixed; inset: 0; z-index: 900; background: var(--overlay); }
.sheet { position: absolute; display: flex; flex-direction: column; background: var(--bg); box-shadow: var(--shadow-pop); overflow: hidden; }
.sheet.right { top: 0; right: 0; bottom: 0; border-left: 1px solid var(--line); }
.sheet.left { top: 0; left: 0; bottom: 0; border-right: 1px solid var(--line); padding-top: env(safe-area-inset-top); }
.sheet.bottom { left: 0; right: 0; bottom: 0; max-height: 88dvh; border-radius: var(--r-xl) var(--r-xl) 0 0; border-top: 1px solid var(--line); padding-bottom: env(safe-area-inset-bottom); }

.sheet-right-enter-active, .sheet-right-leave-active, .sheet-left-enter-active, .sheet-left-leave-active,
.sheet-bottom-enter-active, .sheet-bottom-leave-active { transition: opacity 200ms var(--ease); }
.sheet-right-enter-active .sheet, .sheet-right-leave-active .sheet, .sheet-left-enter-active .sheet,
.sheet-left-leave-active .sheet, .sheet-bottom-enter-active .sheet, .sheet-bottom-leave-active .sheet { transition: transform 240ms var(--ease); }
.sheet-right-enter-from, .sheet-right-leave-to, .sheet-left-enter-from, .sheet-left-leave-to,
.sheet-bottom-enter-from, .sheet-bottom-leave-to { opacity: 0; }
.sheet-right-enter-from .sheet, .sheet-right-leave-to .sheet { transform: translateX(100%); }
.sheet-left-enter-from .sheet, .sheet-left-leave-to .sheet { transform: translateX(-100%); }
.sheet-bottom-enter-from .sheet, .sheet-bottom-leave-to .sheet { transform: translateY(100%); }
</style>
