import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  {
    path: '/',
    component: () => import('../components/AppShell.vue'),
    redirect: '/chat',
    children: [
      { path: 'chat', name: 'chat', component: () => import('../pages/ChatView.vue'), meta: { title: '问答' } },
      { path: 'kb', name: 'kb', component: () => import('../pages/KnowledgeView.vue'), meta: { title: '知识库' } },
      { path: 'quiz', name: 'quiz', component: () => import('../pages/QuizView.vue'), meta: { title: '模拟面试' } },
      { path: 'records', name: 'records', component: () => import('../pages/RecordsView.vue'), meta: { title: '复盘' } },
    ],
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

export default router
