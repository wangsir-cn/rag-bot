import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  { path: '/', redirect: '/chat' },
  { path: '/login', name: 'login', component: () => import('../views/LoginView.vue'), meta: { public: true, title: '登录' } },
  { path: '/register', name: 'register', component: () => import('../views/RegisterView.vue'), meta: { public: true, title: '注册' } },
  { path: '/forgot-password', name: 'forgot', component: () => import('../views/ForgotPasswordView.vue'), meta: { public: true, title: '忘记密码' } },
  { path: '/reset-password', name: 'reset', component: () => import('../views/ResetPasswordView.vue'), meta: { public: true, title: '重置密码' } },
  { path: '/change-password', name: 'changePassword', component: () => import('../views/ChangePasswordView.vue'), meta: { title: '修改密码' } },
  {
    path: '/chat', name: 'chat', component: () => import('../views/ChatView.vue'), meta: { title: '智能问答' }
  },
  {
    path: '/knowledge', name: 'knowledge', component: () => import('../views/KnowledgeView.vue'), meta: { title: '知识库管理' }
  },
  {
    path: '/models', name: 'models', component: () => import('../views/ModelView.vue'), meta: { title: '模型配置' }
  },
  {
    path: '/debug', name: 'debug', component: () => import('../views/DebugView.vue'), meta: { title: '检索调试' }
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach((to) => {
  const token = localStorage.getItem('rag_token')
  const user = JSON.parse(localStorage.getItem('rag_user') || '{}')

  // 未登录访问受保护页面 -> 登录
  if (!to.meta.public && !token) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  // 已登录强制改密：只允许去改密页或登出
  if (token && user.mustChangePassword && to.path !== '/change-password') {
    return { path: '/change-password' }
  }
  // 已登录访问登录/注册页 -> 首页
  if (token && (to.path === '/login' || to.path === '/register') && !user.mustChangePassword) {
    return { path: '/chat' }
  }
  return true
})

export default router
