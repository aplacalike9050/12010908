<template>
  <div class="page">
    <div class="toolbar">
      <h2>API 调用窗口</h2>
      <button class="primary-btn" :disabled="loading" @click="sendRequest">
        {{ loading ? '调用中...' : '发送请求' }}
      </button>
    </div>

    <section class="grid two align-start">
      <div class="card form-card">
        <label>
          网关 API Key
          <input v-model="apiKey" placeholder="sk-pgw-..." />
        </label>
        <div class="form-row">
          <label>
            模型
            <select v-model="requestBody.model" :disabled="requestBody.credential_mode === 'CLIENT_PROVIDED'">
              <option v-for="model in systemModelOptions" :key="model.value" :value="model.value">
                {{ model.label }}（{{ model.value }}）
              </option>
            </select>
          </label>
          <label>
            密钥模式
            <select v-model="requestBody.credential_mode">
              <option>SYSTEM_DEFAULT</option>
              <option>CLIENT_PROVIDED</option>
            </select>
          </label>
        </div>
        <div class="form-row">
          <label>
            策略 ID
            <select v-model.number="requestBody.privacy_policy_id">
              <option :value="null">使用客户端默认策略</option>
              <option v-for="profile in policyOptions" :key="profile.id" :value="profile.id">
                ID {{ profile.id }} - {{ profile.profileName }}
              </option>
            </select>
          </label>
          <label>
            模型密钥 ID
            <select v-model.number="requestBody.model_credential_id" :disabled="requestBody.credential_mode !== 'CLIENT_PROVIDED'">
              <option :value="null">不使用自定义密钥</option>
              <option v-for="item in credentialOptions" :key="item.id" :value="item.id">
                ID {{ item.id }} - {{ item.credentialName }} / {{ item.modelName }}
              </option>
            </select>
          </label>
        </div>
        <p class="hint">
          系统默认模式可选择 DeepSeek / Gemini / ChatGPT；自定义密钥模式会使用选中的模型密钥配置。策略 ID 只展示真实存在的自定义配置；如需使用系统默认配置，请选择“使用客户端默认策略”。
        </p>
        <label>
          OpenAI 请求 JSON
          <textarea v-model="messagesJson" rows="14"></textarea>
        </label>
        <div class="quick-generate">
          <label>
            快捷生成用户消息
            <textarea v-model="quickContent" rows="4" placeholder="输入用户消息内容，点击生成请求后自动写入上方 JSON"></textarea>
          </label>
          <button class="secondary-btn" type="button" @click="generateMessages">生成请求</button>
        </div>
      </div>

      <div class="playground-result">
        <div class="card">
          <div class="result-header">
            <h3>返回结果</h3>
            <label class="inline-check stream-toggle">
              <input v-model="requestBody.stream" type="checkbox" />
              流式响应
            </label>
          </div>
          <div class="stream-content">{{ streamContent || '等待流式输出...' }}</div>
        </div>
        <div class="card">
          <h3>完整调用结果</h3>
          <div v-if="error" class="alert error">{{ error }}</div>
          <div v-if="errorDetail" class="alert warn">
            <div><strong>拦截层：</strong>{{ sourceText(errorDetail.source) }}</div>
            <div><strong>规则/标签：</strong>{{ errorDetail.label || '-' }}</div>
            <div><strong>风险分：</strong>{{ errorDetail.score ?? '-' }}</div>
            <div><strong>原因：</strong>{{ errorDetail.reason || '-' }}</div>
          </div>
          <pre>{{ responseText }}</pre>
        </div>
      </div>
    </section>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { chatCompletions, chatCompletionsStream, modelCredentials, policies, systemModels } from '@/api/http'
import { authState } from '@/stores/auth'

const apiKey = ref(localStorage.getItem('aiprivacy.gatewayApiKey') || 'sk-pgw-postman-demo')
const PLAYGROUND_STATE_KEY = 'aiprivacy.playground.state'
const savedState = JSON.parse(localStorage.getItem(PLAYGROUND_STATE_KEY) || 'null')
const loading = ref(false)
const error = ref('')
const errorDetail = ref(null)
const response = ref(null)
const streamContent = ref('')
const systemModelOptions = ref([
  { label: 'DeepSeek', value: 'deepseek' },
  { label: 'Gemini', value: 'gemini' },
  { label: 'ChatGPT / OpenAI', value: 'chatgpt' },
])
const policyOptions = ref([])
const credentialOptions = ref([])

const requestBody = reactive(savedState?.requestBody || {
  model: 'deepseek',
  stream: false,
  credential_mode: 'SYSTEM_DEFAULT',
  privacy_policy_id: null,
  model_credential_id: null,
  metadata: {
    request_id: `console-${Date.now()}`,
    conversation_id: 'console-demo-conv',
  },
})

const messagesJson = ref(savedState?.messagesJson || JSON.stringify([
  { role: 'system', content: '你是一个严谨的客服助手。' },
  { role: 'user', content: '我叫张三，手机号是13800138000，请帮我写一段预约确认短信。' },
], null, 2))
const quickContent = ref(savedState?.quickContent || '')

const responseText = computed(() => (response.value ? JSON.stringify(response.value, null, 2) : '等待调用...'))

async function sendRequest() {
  loading.value = true
  error.value = ''
  errorDetail.value = null
  response.value = null
  streamContent.value = ''
  try {
    localStorage.setItem('aiprivacy.gatewayApiKey', apiKey.value)
    persistState()
    const body = {
      ...requestBody,
      privacy_policy_id: requestBody.privacy_policy_id || undefined,
      model_credential_id: requestBody.model_credential_id || undefined,
      messages: JSON.parse(messagesJson.value),
    }
    if (body.stream) {
      const streamResult = await chatCompletionsStream(apiKey.value, body, (delta, content) => {
        streamContent.value = content
      })
      response.value = {
        object: 'console.stream_result',
        model: body.model,
        content: streamResult.content,
        chunks: streamResult.chunks,
      }
    } else {
      response.value = await chatCompletions(apiKey.value, body)
      streamContent.value = response.value?.choices?.[0]?.message?.content || ''
    }
  } catch (ex) {
    error.value = ex.message || '调用失败'
    errorDetail.value = ex.data || null
  } finally {
    loading.value = false
  }
}

function sourceText(source) {
  return {
    HEURISTIC_RULE: '第二层：启发式黑名单规则',
    MODEL: '第三层：本地 Prompt 注入模型',
  }[source] || source || '-'
}

async function loadOptions() {
  const [modelRes, policyRes, credentialRes] = await Promise.all([
    systemModels(authState.token),
    policies(authState.token),
    modelCredentials(authState.token),
  ])
  systemModelOptions.value = modelRes.data || systemModelOptions.value
  policyOptions.value = (policyRes.data || []).filter((item) => !item.systemDefault)
  credentialOptions.value = credentialRes.data || []
  const selectedPolicyExists = policyOptions.value.some((item) => item.id === requestBody.privacy_policy_id)
  if (requestBody.privacy_policy_id && !selectedPolicyExists) {
    requestBody.privacy_policy_id = null
  }
}

function generateMessages() {
  messagesJson.value = JSON.stringify([
    {
      role: 'user',
      content: quickContent.value,
    },
  ], null, 2)
  persistState()
}

function persistState() {
  localStorage.setItem(PLAYGROUND_STATE_KEY, JSON.stringify({
    requestBody,
    messagesJson: messagesJson.value,
    quickContent: quickContent.value,
  }))
}

watch(() => requestBody.model_credential_id, (id) => {
  const selected = credentialOptions.value.find((item) => item.id === id)
  if (selected?.modelName) {
    requestBody.model = selected.modelName
  }
})

onMounted(() => loadOptions().catch(() => {}))

watch([requestBody, messagesJson, quickContent], persistState, { deep: true })
</script>
