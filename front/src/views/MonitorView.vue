<template>
  <div class="page">
    <div class="toolbar">
      <h2>监控面板</h2>
      <button class="secondary-btn" :disabled="loading" @click="loadData">刷新</button>
    </div>

    <div v-if="error" class="alert error">{{ error }}</div>

    <section class="grid four">
      <div class="metric-card"><span>总调用量</span><strong>{{ metrics.totalCalls ?? '-' }}</strong></div>
      <div class="metric-card"><span>成功调用</span><strong>{{ metrics.successCalls ?? '-' }}</strong></div>
      <div class="metric-card"><span>失败调用</span><strong>{{ metrics.failedCalls ?? '-' }}</strong></div>
      <div class="metric-card"><span>拦截次数</span><strong>{{ metrics.blockedCalls ?? '-' }}</strong></div>
    </section>

    <section class="grid two">
      <div class="card">
        <h3>服务健康</h3>
        <table>
          <tbody>
            <tr><td>状态</td><td><span class="status ok">{{ healthData.status || 'UNKNOWN' }}</span></td></tr>
            <tr><td>服务</td><td>{{ healthData.service || '-' }}</td></tr>
            <tr><td>更新时间</td><td>{{ healthData.timestamp || '-' }}</td></tr>
          </tbody>
        </table>
      </div>
      <div class="card">
        <h3>Resilience4j 状态</h3>
        <p class="hint">
          这些是当前稳定性组件的瞬时状态，不是累计调用次数。少量成功调用后可能看起来不变：
          RateLimiter 会按分钟刷新额度，Bulkhead 只在并发调用期间下降，CircuitBreaker 只有达到滑动窗口统计量后失败率才明显变化。
        </p>
        <table>
          <tbody>
            <tr><td>RateLimiter 可用额度</td><td>{{ resilience.rateLimiterAvailablePermissions ?? '-' }}</td></tr>
            <tr><td>Bulkhead 可用并发</td><td>{{ resilience.bulkheadAvailableConcurrentCalls ?? '-' }}</td></tr>
            <tr><td>CircuitBreaker 状态</td><td>{{ resilience.circuitBreakerState || '-' }}</td></tr>
            <tr><td>失败率</td><td>{{ resilience.circuitBreakerFailureRate ?? '-' }}</td></tr>
          </tbody>
        </table>
      </div>
    </section>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { health, adminMetricsSummary } from '@/api/http'
import { authState } from '@/stores/auth'

const loading = ref(false)
const error = ref('')
const healthData = ref({})
const metrics = ref({})
const resilience = computed(() => metrics.value.resilience || healthData.value.resilience || {})

async function loadData() {
  loading.value = true
  error.value = ''
  try {
    const [healthRes, metricsRes] = await Promise.all([health(), adminMetricsSummary(authState.token)])
    healthData.value = healthRes.data || {}
    metrics.value = metricsRes.data || {}
  } catch (ex) {
    error.value = ex.message || '监控数据加载失败'
  } finally {
    loading.value = false
  }
}

onMounted(loadData)
</script>
