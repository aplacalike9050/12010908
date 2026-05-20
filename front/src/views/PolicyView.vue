<template>
  <div class="page">
    <div class="toolbar">
      <h2>策略配置</h2>
      <div class="toolbar-actions">
        <button class="ghost-btn" :disabled="!currentProfile || currentProfile.systemDefault" @click="deleteCurrent">删除当前配置</button>
        <button class="secondary-btn" :disabled="!currentProfile || currentProfile.systemDefault" @click="updateCurrent">更新当前配置</button>
        <button class="primary-btn" :disabled="!currentProfile" @click="saveRules">保存为新配置</button>
      </div>
    </div>

    <section class="card">
      <div v-if="error" class="alert error">{{ error }}</div>
      <div v-if="message" class="alert ok">{{ message }}</div>
      <label>
        策略档案
        <select v-model.number="selectedProfileId">
          <option v-for="profile in profiles" :key="profile.id" :value="profile.id">
            ID {{ profile.id }} - {{ profile.profileName }} v{{ profile.version }}
          </option>
        </select>
      </label>
      <p v-if="currentProfile" class="hint">
        当前策略 ID：{{ currentProfile.id }}。系统默认配置仅用于查看或作为模板，API 调用时选择“客户端默认策略”即可使用默认策略。
      </p>
      <div v-if="currentProfile" class="detail-box">
        <div><strong>配置名称：</strong>{{ currentProfile.profileName }}</div>
        <div><strong>版本：</strong>{{ currentProfile.version }}</div>
        <div><strong>类型：</strong>{{ currentProfile.systemDefault ? '系统默认配置' : '开发者自定义配置' }}</div>
        <div><strong>当前已保存配置数：</strong>{{ savedProfileCount }}/10</div>
      </div>
      <p class="hint">Prompt 注入的结构化边界防护默认开启且不可关闭；黑名单和分类模型命中后可选择拦截，或仅记录审计后继续进入隐私检测流程。</p>
      <div v-if="currentProfile" class="policy-group">
        <h3>Prompt注入检测</h3>
        <table>
          <thead>
            <tr><th>检测方式</th><th>处理方式</th></tr>
          </thead>
          <tbody>
            <tr v-for="rule in promptInjectionRules" :key="rule.privacyType">
              <td>{{ rule.displayName }}</td>
              <td>
                <select v-model="rule.action" :disabled="currentProfile.systemDefault">
                  <option value="BLOCK">拦截</option>
                  <option value="RECORD">记录</option>
                </select>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-for="level in riskGroups" :key="level" class="policy-group">
        <h3>{{ riskTitle(level) }}</h3>
        <table>
          <thead>
            <tr><th>隐私类型</th><th>类型编码</th><th>处理方式</th><th>是否启用</th></tr>
          </thead>
          <tbody>
            <tr v-for="rule in groupedRules[level]" :key="rule.privacyType">
              <td>{{ rule.displayName }}</td>
              <td>{{ rule.privacyType }}</td>
              <td>{{ defaultActionLabel(rule.privacyType) }}</td>
              <td><input class="policy-checkbox" type="checkbox" v-model="rule.enabled" :disabled="rule.forced || currentProfile.systemDefault" /></td>
            </tr>
          </tbody>
        </table>
      </div>
      <p v-if="profiles.length === 0" class="hint">暂无策略档案，请先创建客户端或刷新页面。</p>
    </section>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { deletePolicy, policies, savePolicyAsNew, updatePolicyRules } from '@/api/http'
import { authState } from '@/stores/auth'

const profiles = ref([])
const selectedProfileId = ref(null)
const error = ref('')
const message = ref('')
const riskGroups = ['S1', 'S2', 'S3']
const promptInjectionTypes = ['PROMPT_INJECTION_BLACKLIST', 'PROMPT_INJECTION_MODEL']
const defaultActionLabels = {
  PROMPT_INJECTION_BLACKLIST: '拦截',
  PROMPT_INJECTION_MODEL: '拦截',
  PER: '假名替换',
  LOC: '掩码/占位符替换',
  ORG: '掩码/占位符替换',
  PHONE: '掩码/占位符替换',
  EMAIL: '掩码/占位符替换',
  ID_CARD: '掩码/占位符替换',
  BANK_CARD: '掩码/占位符替换',
  ADDRESS: '掩码/占位符替换',
  IPV4: '掩码/占位符替换',
  MAC: '掩码/占位符替换',
  PASSPORT: '掩码/占位符替换',
  SSN: '掩码/占位符替换',
  API_KEY: '强制拦截',
  JWT_TOKEN: '强制拦截',
  AWS_ACCESS_KEY: '强制拦截',
  PRIVATE_KEY_BLOCK: '强制拦截',
}

const currentProfile = computed(() => profiles.value.find((item) => item.id === selectedProfileId.value))
const savedProfileCount = computed(() => profiles.value.filter((item) => !item.systemDefault).length)
const groupedRules = computed(() => {
  const output = { S1: [], S2: [], S3: [] }
  ;(currentProfile.value?.rules || []).forEach((rule) => {
    const level = normalizedRiskLevel(rule)
    output[level]?.push(rule)
  })
  return output
})
const promptInjectionRules = computed(() =>
  (currentProfile.value?.rules || []).filter((rule) => promptInjectionTypes.includes(rule.privacyType)),
)

function riskTitle(level) {
  return {
    S1: 'S1 低敏可配置类型',
    S2: 'S2 高敏可配置类型',
    S3: 'S3 密钥类强制保护',
  }[level]
}

function defaultActionLabel(privacyType) {
  return defaultActionLabels[privacyType] || '掩码/占位符替换'
}

function normalizedRiskLevel(rule) {
  if (['LOC', 'ORG', 'ADDRESS'].includes(rule?.privacyType)) {
    return 'S2'
  }
  return rule?.riskLevel || 'S2'
}

async function loadPolicies() {
  error.value = ''
  const res = await policies(authState.token)
  profiles.value = res.data || []
  selectedProfileId.value = profiles.value[0]?.id || null
}

async function saveRules() {
  message.value = ''
  error.value = ''
  try {
    const name = window.prompt('请输入新策略配置名称', `${currentProfile.value.profileName} - 新配置`)
    if (!name) return
    const saved = await savePolicyAsNew(authState.token, currentProfile.value.id, name, currentProfile.value.rules)
    message.value = `已新增策略配置：${saved.data.profileName}`
    await loadPolicies()
    selectedProfileId.value = saved.data.id
  } catch (ex) {
    error.value = ex.message || '策略保存失败'
  }
}

async function updateCurrent() {
  if (!currentProfile.value || currentProfile.value.systemDefault) return
  message.value = ''
  error.value = ''
  try {
    const updated = await updatePolicyRules(authState.token, currentProfile.value.id, currentProfile.value.rules)
    message.value = `已更新策略配置：${updated.data.profileName}`
    await loadPolicies()
    selectedProfileId.value = updated.data.id
  } catch (ex) {
    error.value = ex.message || '策略更新失败'
  }
}

async function deleteCurrent() {
  if (!currentProfile.value || currentProfile.value.systemDefault) return
  if (!window.confirm(`确认删除策略配置 ${currentProfile.value.profileName}？`)) return
  try {
    await deletePolicy(authState.token, currentProfile.value.id)
    message.value = '策略配置已删除'
    await loadPolicies()
  } catch (ex) {
    error.value = ex.message || '策略删除失败'
  }
}

onMounted(() => loadPolicies().catch((ex) => { error.value = ex.message || '策略加载失败' }))
</script>
