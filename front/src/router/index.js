import { createRouter, createWebHistory } from 'vue-router'
import { authState } from '@/stores/auth'
import ConsoleLayout from '@/layouts/ConsoleLayout.vue'
import LoginView from '@/views/LoginView.vue'
import HomeView from '@/views/HomeView.vue'
import DocsView from '@/views/DocsView.vue'
import PlaygroundView from '@/views/PlaygroundView.vue'
import MonitorView from '@/views/MonitorView.vue'
import AuditView from '@/views/AuditView.vue'
import PolicyView from '@/views/PolicyView.vue'
import ClientView from '@/views/ClientView.vue'
import ModelCredentialView from '@/views/ModelCredentialView.vue'
import PasswordView from '@/views/PasswordView.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', name: 'login', component: LoginView },
    {
      path: '/',
      component: ConsoleLayout,
      meta: { requiresAuth: true },
      children: [
        { path: '', redirect: '/overview' },
        { path: 'overview', name: 'overview', component: HomeView, meta: { title: '项目展示' } },
        { path: 'docs', name: 'docs', component: DocsView, meta: { title: 'API 文档' } },
        { path: 'playground', name: 'playground', component: PlaygroundView, meta: { title: 'API 调用窗口' } },
        { path: 'monitor', name: 'monitor', component: MonitorView, meta: { title: '监控面板' } },
        { path: 'audit', name: 'audit', component: AuditView, meta: { title: '审计日志' } },
        { path: 'policies', name: 'policies', component: PolicyView, meta: { title: '策略配置' } },
        { path: 'clients', name: 'clients', component: ClientView, meta: { title: '客户端管理' } },
        { path: 'model-credentials', name: 'modelCredentials', component: ModelCredentialView, meta: { title: '模型密钥' } },
        { path: 'password', name: 'password', component: PasswordView, meta: { title: '修改密码' } },
      ],
    },
  ],
})

router.beforeEach((to) => {
  if (to.meta.requiresAuth && !authState.user) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  if (to.name === 'login' && authState.user) {
    return { name: 'overview' }
  }
  return true
})

export default router
