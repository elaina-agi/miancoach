import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import vueDevTools from 'vite-plugin-vue-devtools'
import { codeInspectorPlugin } from 'code-inspector-plugin'

export default defineConfig({
  plugins: [
    vue(),
    // Vue 官方开发者工具(仅 dev):组件树/props 状态检查,左下角浮标进入
    vueDevTools(),
    // 按住 Shift+Alt 点击页面元素,VSCode 打开对应 .vue 的对应行(仅 dev)
    codeInspectorPlugin({ bundler: 'vite' }),
  ],
  server: {
    port: 5173,
    // 开发期把 /api 代理到后端,前端免跨域(SSE 也走同源)。
    // 同时跑两套后端对比时用 VITE_API_TARGET=http://localhost:8081 指到另一个
    proxy: {
      '/api': {
        target: process.env.VITE_API_TARGET ?? 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
