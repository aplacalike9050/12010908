<template>
  <div class="page">
    <section class="hero-card">
      <div>
        <p class="eyebrow">Privacy Gateway Middleware</p>
        <h1>AIPrivacy</h1>
        <p class="hero-text">
          面向第三方后端调用的大语言模型隐私网关，提供 OpenAI 兼容接口、隐私脱敏、Prompt 注入防护、
          响应还原、审计与稳定性保护。
        </p>
        <div class="hero-actions">
          <RouterLink class="primary-btn" to="/playground">开始调用</RouterLink>
          <RouterLink class="ghost-btn" to="/docs">查看文档</RouterLink>
        </div>
      </div>
      <div class="flow-panel">
        <div v-for="step in flow" :key="step" class="flow-node">{{ step }}</div>
      </div>
    </section>

    <section class="grid four">
      <div v-for="item in capabilities" :key="item.title" class="metric-card">
        <span class="metric-icon">{{ item.icon }}</span>
        <h3>{{ item.title }}</h3>
        <p>{{ item.desc }}</p>
      </div>
    </section>
  </div>
</template>

<script setup>
const flow = ['客户端鉴权', 'Prompt 注入检测', '隐私识别与脱敏', '模型路由调用', '响应还原', '审计落库']

const capabilities = [
  { icon: 'API', title: 'OpenAI 兼容', desc: '统一使用 /v1/chat/completions，支持流式与非流式。' },
  { icon: 'PII', title: '隐私保护', desc: '支持 S1/S2 可配置保护和 S3 密钥类强制拦截。' },
  { icon: 'SEC', title: '注入防护', desc: '启发式规则、本地模型检测和结构化边界三层防护。' },
  { icon: 'OPS', title: '稳定审计', desc: 'Resilience4j 稳定性保护和异步 JSONB 审计。' },
]
</script>
