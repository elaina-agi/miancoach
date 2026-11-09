<template>
  <div class="favorite-control">
    <button type="button" class="btn btn-secondary btn-sm favorite-btn" :class="{ saved: favorited === true }"
            :disabled="busy" :aria-busy="busy" :aria-pressed="favorited === null ? undefined : favorited"
            :aria-label="buttonLabel" :aria-describedby="error ? errorId : undefined"
            :title="buttonLabel" @click="toggle">
      <LoaderCircle v-if="busy" :size="14" class="spin" aria-hidden="true" />
      <RotateCcw v-else-if="error" :size="14" aria-hidden="true" />
      <BookmarkCheck v-else-if="favorited" :size="14" aria-hidden="true" />
      <Bookmark v-else :size="14" aria-hidden="true" />
      <span aria-live="polite">{{ text }}</span>
    </button>
    <p v-if="error" :id="errorId" class="favorite-error" role="alert">{{ error }}</p>
  </div>
</template>

<script setup lang="ts">
import { computed, toRef, useId } from 'vue'
import { Bookmark, BookmarkCheck, LoaderCircle, RotateCcw } from 'lucide-vue-next'
import { authReady } from '../lib/auth'
import { useQuestionFavorite } from '../lib/favorites'

const props = defineProps<{ chunkId: number }>()
const { favorited, loading, saving, loginPending, error, busy, toggle } = useQuestionFavorite(toRef(props, 'chunkId'))
const errorId = useId()
const text = computed(() => {
  if (!authReady.value || loading.value) return '读取收藏…'
  if (saving.value) return '保存中…'
  if (loginPending.value) return '等待登录…'
  if (error.value) return '重试收藏状态'
  return favorited.value ? '已收藏' : '收藏题目'
})
const buttonLabel = computed(() => !busy.value && !error.value && favorited.value ? '取消收藏这道题' : text.value)
</script>

<style scoped>
.favorite-control { display: flex; flex-wrap: wrap; align-items: center; gap: 6px 10px; min-width: 0; }
.favorite-btn.saved { color: var(--accent); border-color: var(--accent-line); background: var(--accent-soft); }
.favorite-error { color: var(--bad); font-size: 12.5px; line-height: 1.6; max-width: 320px; overflow-wrap: anywhere; }
@media (max-width: 720px) {
  .favorite-btn { min-height: 36px; }
  .favorite-error { max-width: 100%; }
}
</style>
