<template>
  <div class="console-shell">
    <header class="topbar">
      <div class="brand">
        <span class="brand-mark">AI</span>
        <span class="brand-name">AIPrivacy</span>
      </div>
      <nav class="top-links">
        <div class="user-menu">
          <span>{{ authState.user?.displayName }}</span>
          <span class="lang">{{ authState.user?.role || 'DEVELOPER' }}</span>
          <RouterLink to="/password">修改密码</RouterLink>
          <button @click="handleLogout">登出</button>
        </div>
      </nav>
    </header>

    <div class="main">
      <aside class="sidebar">
        <div class="version">AIPrivacy 控制台</div>
        <RouterLink v-for="item in navItems" :key="item.path" :to="item.path" class="nav-item">
          <span class="nav-icon">{{ item.icon }}</span>
          <span>{{ item.label }}</span>
        </RouterLink>
      </aside>

      <section class="content">
        <div class="breadcrumb">
          <span>public</span>
          <span>/</span>
          <strong>{{ route.meta.title || '控制台' }}</strong>
        </div>
        <RouterView />
      </section>
    </div>
  </div>
</template>

<script setup>
import { useRoute, useRouter } from 'vue-router'
import { authState, logout } from '@/stores/auth'

const route = useRoute()
const router = useRouter()

const navItems = [
  { path: '/overview', label: '项目展示', icon: '概' },
  { path: '/docs', label: 'API 文档', icon: '文' },
  { path: '/playground', label: 'API 调用窗口', icon: '调' },
  { path: '/monitor', label: '监控面板', icon: '监' },
  { path: '/audit', label: '审计日志', icon: '审' },
  { path: '/policies', label: '策略配置', icon: '策' },
  { path: '/clients', label: '客户端管理', icon: '客' },
  { path: '/model-credentials', label: '模型密钥', icon: '密' },
]

function handleLogout() {
  logout()
  router.push('/login')
}
</script>
