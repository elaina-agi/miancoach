// 图片点击放大(效果同原站的 medium-zoom):图片从原位置平滑放大到屏幕中央,点任意处 / Esc / 滚轮缩回原位。
// MarkdownView 里点到图片时调 zoomImage(),由全局唯一的 ImageZoomHost 渲染。
import { shallowRef } from 'vue'

export const zoomTarget = shallowRef<HTMLImageElement | null>(null)

export function zoomImage(img: HTMLImageElement): void {
  // 还没加载出来或加载失败的图拿不到原始尺寸,不放大
  if (zoomTarget.value || !img.complete || !img.naturalWidth) return
  zoomTarget.value = img
}
