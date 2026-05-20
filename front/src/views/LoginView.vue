<template>
  <div class="login-page">
    <div class="login-card">
      <div class="login-logo">AIPrivacy</div>
      <p class="login-subtitle">大语言模型隐私网关开发者控制台</p>
      <div class="auth-tabs">
        <button :class="{ active: mode === 'login' }" @click="mode = 'login'">登录</button>
        <button :class="{ active: mode === 'register' }" @click="mode = 'register'">注册</button>
      </div>
      <form @submit.prevent="submit">
        <label>
          用户名
          <input v-model="form.username" placeholder="developer" />
        </label>
        <label>
          密码
          <input v-model="form.password" type="password" placeholder="至少 6 位" />
        </label>
        <button class="primary-btn full" type="submit">{{ mode === 'login' ? '登录' : '注册并创建默认客户端' }}</button>
      </form>
      <div v-if="error" class="alert error">{{ error }}</div>
      <div v-if="registerTip" class="alert ok">{{ registerTip }}</div>
      <p class="hint">注册用户默认是开发者身份。登录后前端保存 Bearer Token，管理接口通过该 Token 鉴权；网关调用接口仍使用客户端 API Key。</p>
    </div>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { setSession } from '@/stores/auth'
import { loginApi, register } from '@/api/http'

const router = useRouter()
const route = useRoute()
const mode = ref('login')
const error = ref('')
const registerTip = ref('')
const form = reactive({
  username: 'developer',
  password: '',
})

async function submit() {
  error.value = ''
  registerTip.value = ''
  try {
    const api = mode.value === 'login' ? loginApi : register
    const result = await api({ username: form.username, password: form.password })
    setSession(result.data)
    if (mode.value === 'register' && result.data.defaultClient?.apiKey) {
      localStorage.setItem('aiprivacy.gatewayApiKey', result.data.defaultClient.apiKey)
      registerTip.value = `默认客户端已创建，API Key：${result.data.defaultClient.apiKey}`
    }
    router.push(route.query.redirect || '/overview')
  } catch (ex) {
    error.value = ex.message || (mode.value === 'login' ? '登录失败' : '注册失败')
  }
}
</script>
