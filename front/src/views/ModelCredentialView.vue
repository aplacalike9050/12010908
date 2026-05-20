<template>
  <div class="page">
    <div class="toolbar">
      <h2>模型密钥</h2>
      <button class="primary-btn" @click="submit">新增密钥</button>
    </div>

    <section class="grid two align-start">
      <div class="card form-card">
        <h3>新增 OpenAI 兼容密钥</h3>
        <div v-if="error" class="alert error">{{ error }}</div>
        <div v-if="message" class="alert ok">{{ message }}</div>
        <label>绑定客户端
          <select v-model.number="form.gatewayClientId">
            <option v-for="client in clients" :key="client.id" :value="client.id">{{ client.clientName }} / {{ client.clientId }}</option>
          </select>
        </label>
        <label>名称<input v-model="form.credentialName" placeholder="DeepSeek 自带 Key" /></label>
        <label>供应商<input v-model="form.provider" placeholder="DEEPSEEK" /></label>
        <label>Base URL<input v-model="form.baseUrl" placeholder="https://api.deepseek.com/v1" /></label>
        <label>模型名<input v-model="form.modelName" placeholder="deepseek-chat" /></label>
        <label>API Key<input v-model="form.apiKey" type="password" placeholder="仅创建时输入，后端加密存储" /></label>
      </div>
      <div class="card">
        <h3>已绑定密钥</h3>
        <table>
          <thead><tr><th>ID</th><th>名称</th><th>供应商</th><th>模型</th><th>状态</th><th>操作</th></tr></thead>
          <tbody>
            <tr v-for="item in credentials" :key="item.id">
              <td>{{ item.id }}</td>
              <td>{{ item.credentialName }}</td>
              <td>{{ item.provider }}</td>
              <td>{{ item.modelName }}</td>
              <td><span class="status ok">{{ item.status }}</span></td>
              <td>
                <button class="link-btn" @click="test(item.id)">测试连通</button>
                <button class="link-btn danger" @click="remove(item.id)">删除</button>
              </td>
            </tr>
          </tbody>
        </table>
        <p v-if="credentials.length === 0" class="hint">暂无模型密钥。</p>
      </div>
    </section>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import {
  clients as fetchClients,
  createModelCredential,
  deleteModelCredential,
  modelCredentials,
  testModelCredential,
} from '@/api/http'
import { authState } from '@/stores/auth'

const clients = ref([])
const credentials = ref([])
const error = ref('')
const message = ref('')
const form = reactive({
  gatewayClientId: null,
  credentialName: 'DeepSeek 自带 Key',
  provider: 'DEEPSEEK',
  baseUrl: 'https://api.deepseek.com/v1',
  modelName: 'deepseek-chat',
  apiKey: '',
})

async function loadData() {
  const [clientRes, credentialRes] = await Promise.all([
    fetchClients(authState.token),
    modelCredentials(authState.token),
  ])
  clients.value = clientRes.data || []
  credentials.value = credentialRes.data || []
  form.gatewayClientId = form.gatewayClientId || clients.value[0]?.id || null
}

async function submit() {
  error.value = ''
  message.value = ''
  try {
    await createModelCredential(authState.token, form)
    message.value = '模型密钥已新增'
    form.apiKey = ''
    await loadData()
  } catch (ex) {
    error.value = ex.message || '新增模型密钥失败'
  }
}

async function test(id) {
  error.value = ''
  message.value = ''
  try {
    const res = await testModelCredential(authState.token, id)
    message.value = res.data?.message || '测试通过'
  } catch (ex) {
    error.value = ex.message || '测试失败'
  }
}

async function remove(id) {
  if (!window.confirm('确认删除该模型密钥？')) return
  await deleteModelCredential(authState.token, id)
  await loadData()
}

onMounted(() => loadData().catch((ex) => { error.value = ex.message || '模型密钥加载失败' }))
</script>
