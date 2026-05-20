<template>
  <div class="page">
    <div class="toolbar">
      <h2>OpenAI 兼容接口文档</h2>
      <button class="secondary-btn" @click="copySample">复制请求示例</button>
    </div>

    <section class="card">
      <h3>主接口</h3>
      <div class="endpoint"><span>POST</span> /v1/chat/completions</div>
      <p>请求头使用 <code>Authorization: Bearer &lt;gatewayApiKey&gt;</code>。调用方传入结构化 messages，网关负责隐私处理和模型转发。</p>
    </section>

    <section class="grid two">
      <div class="card">
        <h3>请求字段</h3>
        <table>
          <tbody>
            <tr><td>model</td><td>模型名或供应商模型名</td></tr>
            <tr><td>messages</td><td>OpenAI 消息数组</td></tr>
            <tr><td>stream</td><td>是否 SSE 流式输出</td></tr>
            <tr><td>credential_mode</td><td>SYSTEM_DEFAULT / CLIENT_PROVIDED</td></tr>
            <tr><td>privacy_policy_id</td><td>指定隐私策略档案</td></tr>
          </tbody>
        </table>
      </div>
      <div class="card">
        <h3>错误类型</h3>
        <table>
          <tbody>
            <tr><td>401</td><td>API Key 无效或客户端停用</td></tr>
            <tr><td>400</td><td>请求缺少 messages 或包含伪造 PII 占位符</td></tr>
            <tr><td>403</td><td>S3 高风险内容或 Prompt 注入被拦截</td></tr>
            <tr><td>429</td><td>限流或并发保护触发</td></tr>
            <tr><td>502/504</td><td>模型调用失败或超时</td></tr>
          </tbody>
        </table>
      </div>
    </section>

    <section class="card">
      <h3>请求示例</h3>
      <pre>{{ sample }}</pre>
    </section>
  </div>
</template>

<script setup>
const sample = JSON.stringify(
  {
    model: 'deepseek-chat',
    stream: false,
    credential_mode: 'SYSTEM_DEFAULT',
    metadata: { request_id: 'console-docs-001', conversation_id: 'console-conv-001' },
    messages: [
      { role: 'system', content: '你是一个严谨的客服助手。' },
      { role: 'user', content: '我叫张三，手机号是13800138000，请写一段预约确认短信。' },
    ],
  },
  null,
  2,
)

function copySample() {
  navigator.clipboard?.writeText(sample)
}
</script>
