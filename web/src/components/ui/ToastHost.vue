<template>
  <Teleport to="body">
    <div class="toasts" aria-live="polite">
      <TransitionGroup name="toast">
        <div v-for="t in toasts" :key="t.id" class="toast" :class="t.kind" role="status" @click="dismiss(t.id)">
          <CircleCheck v-if="t.kind === 'success'" :size="16" />
          <CircleAlert v-else-if="t.kind === 'error'" :size="16" />
          <Info v-else :size="16" />
          <span>{{ t.text }}</span>
        </div>
      </TransitionGroup>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import { CircleAlert, CircleCheck, Info } from 'lucide-vue-next'
import { dismiss, toasts } from '../../lib/toast'
</script>

<style scoped>
.toasts { position: fixed; top: calc(12px + env(safe-area-inset-top)); left: 50%; transform: translateX(-50%); z-index: 1100; display: flex; flex-direction: column; align-items: center; gap: 8px; pointer-events: none; width: max-content; max-width: calc(100vw - 32px); }
.toast { pointer-events: auto; display: flex; align-items: flex-start; gap: 8px; padding: 9px 14px; border-radius: var(--r); background: var(--ink); color: var(--bg); font-size: 13.5px; line-height: 1.5; box-shadow: var(--shadow-pop); cursor: pointer; }
.toast svg { margin-top: 2px; }
.toast.success svg { color: var(--accent-hover); }
.toast.error svg { color: #f2948a; }
[data-theme='dark'] .toast.success svg { color: var(--accent); }
[data-theme='dark'] .toast.error svg { color: var(--bad); }
.toast-enter-active, .toast-leave-active { transition: opacity 180ms var(--ease), transform 180ms var(--ease); }
.toast-enter-from, .toast-leave-to { opacity: 0; transform: translateY(-6px); }
</style>
