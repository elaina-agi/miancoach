<template>
  <Teleport to="body">
    <div v-if="src" class="iz" :class="{ on: zoomed }" role="dialog" aria-modal="true" aria-label="查看大图"
         @click="close" @wheel.passive="close">
      <img :src="src" :alt="alt" class="iz-img" :style="imgStyle" @transitionend="onTransitionEnd" />
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref, shallowRef, watch } from 'vue'
import { useOverlay } from '../../lib/overlay'
import { zoomTarget } from '../../lib/imageZoom'

const src = ref('')
const alt = ref('')
const rect = shallowRef<DOMRect | null>(null)
const transform = ref('none')
const zoomed = ref(false)
let origin: HTMLImageElement | null = null
let fallback: number | undefined

// 放大的图盖在原图位置上,用 transform 移到屏幕中央并放大;缩回时 transform 归零,正好落回原位
const imgStyle = computed(() => {
  const r = rect.value
  return r ? { left: `${r.left}px`, top: `${r.top}px`, width: `${r.width}px`, height: `${r.height}px`, transform: transform.value } : {}
})

useOverlay(zoomed, close)

watch(zoomTarget, (img) => { if (img) open(img) })

function open(img: HTMLImageElement) {
  clearTimeout(fallback)
  origin = img
  const r = img.getBoundingClientRect()
  rect.value = r
  src.value = img.currentSrc || img.src
  alt.value = img.alt
  // 原图藏起来,看上去就是它本身被"拿起来"放大
  img.style.visibility = 'hidden'
  // 先在原位置画出来,隔两帧再加放大的 transform,transition 才有起点
  requestAnimationFrame(() => requestAnimationFrame(() => {
    if (origin !== img) return
    transform.value = fitTransform(img, r)
    zoomed.value = true
    window.addEventListener('resize', close)
  }))
}

/**
 * 放大到刚好装进屏幕(四周留白),但不超过图片原始尺寸——再大就糊了。
 * 原本就比屏幕大的长图不缩小(缩小就看不清了),只移到中央。
 */
function fitTransform(img: HTMLImageElement, r: DOMRect): string {
  const vw = document.documentElement.clientWidth
  const vh = window.innerHeight
  const m = vw <= 720 ? 12 : 32
  const scale = Math.max(1, Math.min((vw - 2 * m) / r.width, (vh - 2 * m) / r.height, img.naturalWidth / r.width))
  const dx = vw / 2 - (r.left + r.width / 2)
  const dy = vh / 2 - (r.top + r.height / 2)
  return `translate(${dx}px, ${dy}px) scale(${scale})`
}

function close() {
  if (!zoomed.value) return
  zoomed.value = false
  transform.value = 'none'
  window.removeEventListener('resize', close)
  // 正常由 transitionend 收尾;万一事件没来(标签页切到后台等),到点也收掉
  fallback = window.setTimeout(finish, 450)
}

function onTransitionEnd(e: TransitionEvent) {
  if (e.propertyName === 'transform' && !zoomed.value) finish()
}

function finish() {
  clearTimeout(fallback)
  if (origin) origin.style.visibility = ''
  origin = null
  src.value = ''
  rect.value = null
  zoomTarget.value = null
}

onBeforeUnmount(() => {
  window.removeEventListener('resize', close)
  finish()
})
</script>

<style scoped>
/* 层级在弹框(1000)之上、提示(1100)之下:手机上从来源面板里点图也能盖住面板 */
.iz { position: fixed; inset: 0; z-index: 1050; cursor: zoom-out; }
.iz::before { content: ''; position: absolute; inset: 0; background: var(--bg); opacity: 0; transition: opacity 300ms var(--ease); }
.iz.on::before { opacity: 0.94; }
.iz-img { position: fixed; max-width: none; border-radius: var(--r); transform-origin: center; transition: transform 300ms var(--ease); }
</style>
