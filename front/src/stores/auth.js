import { reactive } from 'vue'

const STORAGE_KEY = 'aiprivacy.console.user'
const TOKEN_KEY = 'aiprivacy.console.token'

const stored = JSON.parse(localStorage.getItem(STORAGE_KEY) || 'null')

export const authState = reactive({
  user: stored || null,
  token: localStorage.getItem(TOKEN_KEY) || '',
})

export function setSession(payload) {
  if (!payload || !payload.user || !payload.token) {
    throw new Error('登录响应无效，请检查用户名、密码或后端认证接口')
  }
  authState.user = payload.user
  authState.token = payload.token
  localStorage.setItem(STORAGE_KEY, JSON.stringify(authState.user))
  localStorage.setItem(TOKEN_KEY, authState.token)
}

export function logout() {
  authState.user = null
  authState.token = ''
  localStorage.removeItem(STORAGE_KEY)
  localStorage.removeItem(TOKEN_KEY)
}

export function changePassword() {
  authState.user = {
    ...authState.user,
    passwordChangedAt: new Date().toISOString(),
  }
  localStorage.setItem(STORAGE_KEY, JSON.stringify(authState.user))
}
