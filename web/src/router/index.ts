import { createRouter, createWebHistory } from 'vue-router'
import AppShell from '../components/AppShell.vue'

declare module 'vue-router' {
  interface RouteMeta { title?: string }
}

// 会话 id、题目 id 放进 URL:刷新不丢当前对话/当前题,也能直接把链接发给别人
const routes = [
  {
    path: '/',
    component: AppShell,
    redirect: '/chat',
    children: [
      { path: 'chat/:sessionId?', name: 'chat', component: () => import('../pages/ChatView.vue'), meta: { title: '问答' } },
      { path: 'kb/:id(\\d+)?', name: 'kb', component: () => import('../pages/KnowledgeView.vue'), meta: { title: '知识库' } },
      { path: 'quiz', name: 'quiz', component: () => import('../pages/QuizView.vue'), meta: { title: '模拟面试' } },
      { path: 'records', name: 'records', component: () => import('../pages/RecordsView.vue'), meta: { title: '复盘' } },
      { path: 'about', name: 'about', component: () => import('../pages/AboutView.vue'), meta: { title: '关于' } },
      // 不在路由守卫里拦:直接打开 /admin 时登录弹框还没挂载,没法在守卫里等用户登录。
      // 页面自己按登录态显示"去登录 / 没有权限",真正的拦截在后端
      { path: 'admin', name: 'admin', component: () => import('../pages/AdminView.vue'), meta: { title: '管理后台' } },
    ],
  },
  { path: '/:pathMatch(.*)*', redirect: '/chat' },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

router.afterEach((to) => {
  document.title = to.meta.title ? `${to.meta.title} · MianCoach` : 'MianCoach · AI 模拟面试官'
})

export default router
