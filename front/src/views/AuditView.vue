<template>
  <div class="page">
    <div class="toolbar">
      <h2>审计日志</h2>
      <button class="secondary-btn" @click="loadRows">刷新</button>
    </div>

    <section class="card">
      <div class="filters">
        <input v-model="filters.keyword" placeholder="Request ID / 客户端 / 模型" />
        <select v-model="filters.riskLevel">
          <option value="ALL">全部风险</option>
          <option value="PROMPT_INJECTION">Prompt注入</option>
          <option value="S1">S1低敏隐私</option>
          <option value="S2">S2高敏隐私</option>
          <option value="S3">S3高危密钥</option>
        </select>
        <select v-model="filters.status"><option value="ALL">全部状态</option><option value="SUCCESS">成功</option><option value="BLOCKED">拦截</option><option value="FAILED">失败</option></select>
        <button class="primary-btn" @click="loadRows">查询</button>
      </div>
      <div v-if="error" class="alert error">{{ error }}</div>
      <table>
        <thead>
          <tr>
            <th>Request ID</th>
            <th class="audit-client-col">客户端</th>
            <th>模型</th>
            <th>状态</th>
            <th>Prompt注入</th>
            <th>隐私风险</th>
            <th class="audit-finding-col">命中字段</th>
            <th>耗时</th>
            <th>时间</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="item in rows" :key="item.id">
            <td>{{ item.requestId }}</td>
            <td class="audit-client-col">{{ item.clientId || '-' }}</td>
            <td>{{ item.modelName || '-' }}</td>
            <td><span :class="['status', statusClass(item)]">{{ statusText(item) }}</span></td>
            <td>{{ item.promptInjectionDetected ? 'True' : 'False' }}</td>
            <td>{{ privacyRiskText(item.privacyRiskLevel) }}</td>
            <td class="audit-finding-col" :title="findingFieldsText(item)">{{ findingFieldsText(item) }}</td>
            <td>{{ item.latencyMs ?? '-' }}ms</td>
            <td>{{ item.createTime || '-' }}</td>
          </tr>
        </tbody>
      </table>
      <p v-if="rows.length === 0" class="hint">暂无审计记录。</p>
    </section>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { auditEvents } from '@/api/http'
import { authState } from '@/stores/auth'

const rows = ref([])
const error = ref('')
const filters = reactive({ keyword: '', riskLevel: 'ALL', status: 'ALL' })

function statusText(item) {
  if (item.blocked) return '拦截'
  if (item.success) return '成功'
  return '失败'
}

function statusClass(item) {
  if (item.blocked) return 'warn'
  if (item.success) return 'ok'
  return 'error'
}

function privacyRiskText(riskLevel) {
  return {
    S1: 'S1低敏隐私',
    S2: 'S2高敏隐私',
    S3: 'S3高危密钥',
  }[riskLevel] || '-'
}

function findingFieldsText(item) {
  const fields = item.findingFields || []
  return fields.length ? fields.join('，') : '-'
}

async function loadRows() {
  error.value = ''
  try {
    const res = await auditEvents(authState.token, filters)
    rows.value = res.data || []
  } catch (ex) {
    error.value = ex.message || '审计查询失败'
  }
}

onMounted(loadRows)
</script>
