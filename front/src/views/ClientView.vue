<template>
  <div class="page">
    <div class="toolbar">
      <h2>客户端管理</h2>
      <button class="primary-btn" @click="createNewClient">创建客户端</button>
    </div>

    <section class="card">
      <div v-if="error" class="alert error">{{ error }}</div>
      <div v-if="createdApiKey" class="alert ok">新客户端 API Key：{{ createdApiKey }}，请立即保存。</div>
      <table>
        <thead>
          <tr><th>Client ID</th><th>应用名称</th><th>状态</th><th>限流/分钟</th><th>并发</th><th>操作</th></tr>
        </thead>
        <tbody>
          <tr v-for="client in clients" :key="client.clientId">
            <td>{{ client.clientId }}</td>
            <td>{{ client.clientName }}</td>
            <td><span class="status ok">{{ client.status }}</span></td>
            <td>{{ client.rateLimitPerMinute }}</td>
            <td>{{ client.concurrentLimit }}</td>
            <td><button class="link-btn" @click="copyClientId(client.clientId)">复制 Client ID</button></td>
          </tr>
        </tbody>
      </table>
      <p v-if="clients.length === 0" class="hint">暂无客户端。</p>
    </section>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { clients as fetchClients, createClient } from '@/api/http'
import { authState } from '@/stores/auth'

const clients = ref([])
const error = ref('')
const createdApiKey = ref('')

async function loadClients() {
  error.value = ''
  const res = await fetchClients(authState.token)
  clients.value = res.data || []
}

async function createNewClient() {
  const clientName = window.prompt('请输入客户端应用名称', '默认接入应用')
  if (!clientName) return
  try {
    const res = await createClient(authState.token, { clientName })
    createdApiKey.value = res.data.apiKey
    await loadClients()
  } catch (ex) {
    error.value = ex.message || '创建客户端失败'
  }
}

function copyClientId(clientId) {
  navigator.clipboard?.writeText(clientId)
}

onMounted(() => loadClients().catch((ex) => { error.value = ex.message || '客户端加载失败' }))
</script>
