const DEFAULT_BASE_URL = ''

class ApiError extends Error {
  constructor(message, data) {
    super(message)
    this.name = 'ApiError'
    this.data = data
  }
}

export async function request(path, options = {}) {
  const { headers = {}, ...restOptions } = options
  const response = await fetch(`${DEFAULT_BASE_URL}${path}`, {
    ...restOptions,
    headers: {
      'Content-Type': 'application/json',
      ...headers,
    },
  })
  const contentType = response.headers.get('content-type') || ''
  const data = contentType.includes('application/json') ? await response.json() : await response.text()
  if (!response.ok) {
    const message = typeof data === 'string' ? data : data?.message || '请求失败'
    throw new ApiError(message, data?.data)
  }
  if (data && typeof data === 'object' && 'code' in data && data.code !== 200) {
    throw new ApiError(data.message || '请求失败', data.data)
  }
  return data
}

export function health() {
  return request('/api/v1/health')
}

export function metricsSummary() {
  return request('/api/v1/metrics/summary')
}

export function authHeader(token) {
  return { Authorization: `Bearer ${token}` }
}

export function register(body) {
  return request('/api/v1/auth/register', { method: 'POST', body: JSON.stringify(body) })
}

export function loginApi(body) {
  return request('/api/v1/auth/login', { method: 'POST', body: JSON.stringify(body) })
}

export function changePasswordApi(token, body) {
  return request('/api/v1/auth/change-password', {
    method: 'POST',
    headers: authHeader(token),
    body: JSON.stringify(body),
  })
}

export function adminMetricsSummary(token) {
  return request('/api/v1/admin/metrics/summary', { headers: authHeader(token) })
}

export function auditEvents(token, params = {}) {
  const query = new URLSearchParams(
    Object.entries(params).filter(([, value]) => value !== undefined && value !== null && value !== '' && value !== 'ALL'),
  )
  return request(`/api/v1/admin/audit-events${query.toString() ? `?${query}` : ''}`, { headers: authHeader(token) })
}

export function policies(token) {
  return request('/api/v1/admin/policies', { headers: authHeader(token) })
}

export function updatePolicyRules(token, profileId, rules) {
  return request(`/api/v1/admin/policies/${profileId}`, {
    method: 'PUT',
    headers: authHeader(token),
    body: JSON.stringify({ rules }),
  })
}

export function savePolicyAsNew(token, profileId, profileName, rules) {
  return request(`/api/v1/admin/policies/${profileId}/rules`, {
    method: 'PUT',
    headers: authHeader(token),
    body: JSON.stringify({ profileName, rules }),
  })
}

export function deletePolicy(token, profileId) {
  return request(`/api/v1/admin/policies/${profileId}`, {
    method: 'DELETE',
    headers: authHeader(token),
  })
}

export function clients(token) {
  return request('/api/v1/admin/clients', { headers: authHeader(token) })
}

export function createClient(token, body) {
  return request('/api/v1/admin/clients', {
    method: 'POST',
    headers: authHeader(token),
    body: JSON.stringify(body),
  })
}

export function modelCredentials(token) {
  return request('/api/v1/admin/model-credentials', { headers: authHeader(token) })
}

export function systemModels(token) {
  return request('/api/v1/admin/system-models', { headers: authHeader(token) })
}

export function createModelCredential(token, body) {
  return request('/api/v1/admin/model-credentials', {
    method: 'POST',
    headers: authHeader(token),
    body: JSON.stringify(body),
  })
}

export function testModelCredential(token, id) {
  return request(`/api/v1/admin/model-credentials/${id}/test`, {
    method: 'POST',
    headers: authHeader(token),
  })
}

export function deleteModelCredential(token, id) {
  return request(`/api/v1/admin/model-credentials/${id}`, {
    method: 'DELETE',
    headers: authHeader(token),
  })
}

export function chatCompletions(apiKey, body) {
  return request('/v1/chat/completions', {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${apiKey}`,
    },
    body: JSON.stringify(body),
  })
}

export async function chatCompletionsStream(apiKey, body, onDelta) {
  const response = await fetch('/v1/chat/completions', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
      Authorization: `Bearer ${apiKey}`,
    },
    body: JSON.stringify({ ...body, stream: true }),
  })
  if (!response.ok) {
    const text = await response.text()
    throw new ApiError(text || '流式调用失败')
  }
  const reader = response.body.getReader()
  const decoder = new TextDecoder('utf-8')
  const chunks = []
  let buffer = ''
  let content = ''

  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    const events = buffer.split(/\r?\n\r?\n/)
    buffer = events.pop() || ''
    for (const event of events) {
      const lines = event.split(/\r?\n/).filter((line) => line.startsWith('data:'))
      for (const line of lines) {
        const dataText = line.replace(/^data:\s*/, '')
        if (!dataText) continue
        if (dataText === '[DONE]') {
          chunks.push('[DONE]')
          continue
        }
        const parsed = JSON.parse(dataText)
        chunks.push(parsed)
        if (parsed.error) {
          throw new ApiError(parsed.error.message || '流式调用失败', parsed.error)
        }
        const delta = parsed.choices?.[0]?.delta?.content || ''
        if (delta) {
          content += delta
          onDelta?.(delta, content, parsed)
        }
      }
    }
  }
  return { content, chunks }
}
