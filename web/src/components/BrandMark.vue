<template>
  <!-- 标志:一页笔记的三行字。和 index.html 的 favicon 是同一个图形 -->
  <svg :width="size" :height="size" viewBox="0 0 24 24" aria-hidden="true">
    <rect width="24" height="24" rx="7" fill="var(--accent)" />
    <path d="M7 8h10M7 12h7M7 16h4" stroke="var(--accent-ink)" stroke-width="2" stroke-linecap="round" />
  </svg>
</template>

<script setup lang="ts">
withDefaults(defineProps<{ size?: number }>(), { size: 24 })
</script>
