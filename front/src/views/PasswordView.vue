<template>
  <div class="page narrow">
    <div class="toolbar">
      <h2>修改密码</h2>
    </div>

    <section class="card form-card">
      <label>当前密码<input v-model="form.oldPassword" type="password" /></label>
      <label>新密码<input v-model="form.newPassword" type="password" /></label>
      <label>确认新密码<input v-model="form.confirmPassword" type="password" /></label>
      <div v-if="message" class="alert ok">{{ message }}</div>
      <div v-if="error" class="alert error">{{ error }}</div>
      <button class="primary-btn" @click="submit">确认修改</button>
      <p class="hint">当前为前端状态演示，后续可对接开发者账号密码接口。</p>
    </section>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { changePasswordApi } from '@/api/http'
import { authState, changePassword } from '@/stores/auth'

const form = reactive({
  oldPassword: '',
  newPassword: '',
  confirmPassword: '',
})
const message = ref('')
const error = ref('')

async function submit() {
  message.value = ''
  error.value = ''
  if (!form.newPassword || form.newPassword !== form.confirmPassword) {
    error.value = '请确认两次输入的新密码一致'
    return
  }
  try {
    await changePasswordApi(authState.token, {
      oldPassword: form.oldPassword,
      newPassword: form.newPassword,
    })
    changePassword()
    message.value = '密码修改成功'
  } catch (ex) {
    error.value = ex.message || '密码修改失败'
  }
}
</script>
