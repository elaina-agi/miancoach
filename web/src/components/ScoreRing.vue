<template>
  <div class="ring" :style="{ width: size + 'px', height: size + 'px' }">
    <svg :width="size" :height="size">
      <circle class="track" :cx="c" :cy="c" :r="r" fill="none" :stroke-width="stroke" />
      <circle class="val" :cx="c" :cy="c" :r="r" fill="none" :stroke-width="stroke"
              :stroke-dasharray="circ" :stroke-dashoffset="offset" :stroke="color" stroke-linecap="round"
              :transform="`rotate(-90 ${c} ${c})`" />
    </svg>
    <div class="label">
      <template v-if="score == null">—</template>
      <template v-else>{{ score }}</template>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(defineProps<{ score: number | null; size?: number }>(), { size: 108 })

const c = computed(() => props.size / 2)
const r = computed(() => c.value - 7)
const stroke = computed(() => Math.max(5, props.size * 0.08))
const circ = computed(() => 2 * Math.PI * r.value)
const offset = computed(() =>
  props.score == null ? circ.value : circ.value * (1 - (props.score ?? 0) / 100))
const color = computed(() => {
  if (props.score == null) return 'var(--border-strong)'
  if (props.score >= 80) return 'var(--accent)'
  if (props.score >= 60) return 'var(--info)'
  if (props.score >= 40) return 'var(--warn)'
  return 'var(--danger)'
})
</script>

<style scoped>
.ring { position: relative; display: inline-grid; place-items: center; }
.ring svg { transform: scaleX(-1); }
.track { stroke: var(--bg-soft); }
.val { transition: stroke-dashoffset 0.6s var(--ease); }
.label {
  position: absolute; inset: 0;
  display: grid; place-items: center;
  font-family: var(--font-display);
  font-weight: 700; font-size: 26px;
}
</style>
