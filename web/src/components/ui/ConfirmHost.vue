<template>
  <Modal v-model:open="visible" :title="shown?.title" :width="380">
    <p v-if="shown?.message" class="msg">{{ shown.message }}</p>
    <template #footer>
      <!-- 危险操作默认聚焦"取消":误按回车不至于直接删掉 -->
      <button class="btn btn-secondary" :data-autofocus="shown?.danger ? '' : undefined" @click="settleConfirm(false)">{{ shown?.cancelText ?? '取消' }}</button>
      <button class="btn" :class="shown?.danger ? 'btn-danger' : 'btn-primary'" :data-autofocus="shown?.danger ? undefined : ''" @click="settleConfirm(true)">
        {{ shown?.confirmText ?? '确定' }}
      </button>
    </template>
  </Modal>
</template>

<script setup lang="ts">
import { computed, shallowRef, watch } from 'vue'
import Modal from './Modal.vue'
import { confirmState as state, settleConfirm } from '../../lib/confirm'

// 关闭动画期间 state 已经清空,文案仍用最后一次的,免得淡出时内容先消失
const shown = shallowRef(state.value)
watch(state, (s) => { if (s) shown.value = s })

// Esc、点遮罩、点右上角 × 都走 set(false),等同于取消
const visible = computed({
  get: () => state.value !== null,
  set: (v) => { if (!v) settleConfirm(false) },
})
</script>

<style scoped>
.msg { color: var(--ink-2); line-height: 1.7; }
</style>
