<template>
  <div ref="root" class="pop-root">
    <slot name="trigger" :open="open" :toggle="toggle" />
    <Transition name="pop">
      <div v-if="open" class="pop" :class="align" role="menu" @click="closeOnClick && (open = false)">
        <slot :close="() => (open = false)" />
      </div>
    </Transition>
  </div>
</template>

<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { useOverlay } from '../../lib/overlay'

// 轻量下拉:账号菜单、服务状态。点外面 / Esc 关闭,不锁背景滚动
withDefaults(defineProps<{ align?: 'left' | 'right'; closeOnClick?: boolean }>(), { align: 'right', closeOnClick: false })

const open = ref(false)
const root = ref<HTMLElement | null>(null)
const toggle = () => { open.value = !open.value }

useOverlay(open, () => { open.value = false }, false)

function onDocDown(e: PointerEvent) {
  if (root.value && !root.value.contains(e.target as Node)) open.value = false
}
watch(open, (v) => {
  if (v) document.addEventListener('pointerdown', onDocDown, true)
  else document.removeEventListener('pointerdown', onDocDown, true)
})
onBeforeUnmount(() => document.removeEventListener('pointerdown', onDocDown, true))
</script>

<style scoped>
.pop-root { position: relative; display: inline-flex; }
.pop { position: absolute; top: calc(100% + 8px); z-index: 800; min-width: 220px; padding: 6px; background: var(--surface); border: 1px solid var(--line); border-radius: var(--r-lg); box-shadow: var(--shadow-pop); }
.pop.right { right: 0; }
.pop.left { left: 0; }
.pop-enter-active, .pop-leave-active { transition: opacity 140ms var(--ease), transform 140ms var(--ease); }
.pop-enter-from, .pop-leave-to { opacity: 0; transform: translateY(-4px); }
</style>
