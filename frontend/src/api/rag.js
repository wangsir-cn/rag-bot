import request from './request'

// ===== 认证 =====
export const apiLogin = (data) => request.post('/auth/login', data)
export const apiRegister = (data) => request.post('/auth/register', data)
export const apiChangePassword = (data) => request.post('/auth/change-password', data)
export const apiForgotPassword = (email) => request.post('/auth/forgot-password', { email })
export const apiResetPassword = (data) => request.post('/auth/reset-password', data)
export const apiMe = () => request.get('/auth/me')

// ===== 模型配置 =====
export const apiListModels = () => request.get('/models')
export const apiCreateModel = (data) => request.post('/models', data)
export const apiUpdateModel = (id, data) => request.put(`/models/${id}`, data)
export const apiDeleteModel = (id) => request.delete(`/models/${id}`)
export const apiSetDefaultModel = (id) => request.post(`/models/${id}/default`)
export const apiTestModel = (id) => request.post(`/models/${id}/test`)

// ===== 知识库 =====
export const apiListKb = () => request.get('/kb')
export const apiCreateKb = (data) => request.post('/kb', data)
export const apiUpdateKb = (id, data) => request.put(`/kb/${id}`, data)
export const apiDeleteKb = (id) => request.delete(`/kb/${id}`)

// ===== 文档与切片 =====
export const apiListDocs = (kbId) => request.get(`/kb/${kbId}/documents`)
export const apiDeleteDoc = (kbId, docId) => request.delete(`/kb/${kbId}/documents/${docId}`)
export const apiReprocessDoc = (kbId, docId) => request.post(`/kb/${kbId}/documents/${docId}/reprocess`)
export const apiListChunks = (kbId, documentId) =>
  request.get(`/kb/${kbId}/documents/chunks`, { params: documentId ? { documentId } : {} })
export const apiCreateChunk = (kbId, data) =>
  request.post(`/kb/${kbId}/documents/chunks`, null, { params: data })
export const apiUpdateChunk = (kbId, chunkId, data) =>
  request.put(`/kb/${kbId}/documents/chunks/${chunkId}`, null, { params: data })
export const apiDeleteChunk = (kbId, chunkId) => request.delete(`/kb/${kbId}/documents/chunks/${chunkId}`)

export function uploadDocument(kbId, file, fileKind, onProgress) {
  const formData = new FormData()
  formData.append('file', file)
  if (fileKind) formData.append('fileKind', fileKind)
  return request.post(`/kb/${kbId}/documents`, formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
    onUploadProgress: (e) => {
      if (e.total) onProgress?.(Math.round((e.loaded * 100) / e.total))
    }
  })
}

/** 下载 QA 模板（带 token 的 fetch，避免鉴权拦截） */
export async function downloadQaTemplate() {
  const token = localStorage.getItem('rag_token')
  const resp = await fetch('/api/templates/qa', {
    headers: token ? { Authorization: `Bearer ${token}` } : {}
  })
  if (!resp.ok) throw new Error('模板下载失败')
  const blob = await resp.blob()
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = 'qa_template.csv'
  a.click()
  URL.revokeObjectURL(url)
}

// ===== 问答会话 =====
export const apiListSessions = () => request.get('/chat/sessions')
export const apiCreateSession = (knowledgeBaseId) => request.post('/chat/sessions', { knowledgeBaseId })
export const apiGetSession = (sessionId) => request.get(`/chat/sessions/${sessionId}`)
export const apiDeleteSession = (sessionId) => request.delete(`/chat/sessions/${sessionId}`)
export const apiRetrieveDebug = (data) => request.post('/chat/retrieve', data)

/** POST + SSE 流式请求 */
export async function streamChat(payload, handlers = {}) {
  const token = localStorage.getItem('rag_token')
  const response = await fetch('/api/chat/stream', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {})
    },
    body: JSON.stringify(payload),
    signal: handlers.signal
  })
  if (!response.ok) {
    let message = `HTTP ${response.status}`
    try { message = (await response.json()).error || message } catch (_) { /* ignore */ }
    throw new Error(message)
  }
  const reader = response.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''
  // 业务层结束标志：收到 done/error 事件即结束，不依赖服务端关闭 HTTP 连接。
  // 经代理转发的 SSE 常保持 keep-alive，reader.read() 不会返回 done:true，
  // 若只等 HTTP 层结束，前端会一直卡在"停止"状态。
  let finished = false
  try {
    while (!finished) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true }).replace(/\r\n/g, '\n')
      let idx = buffer.indexOf('\n\n')
      while (idx >= 0) {
        const frame = buffer.slice(0, idx)
        buffer = buffer.slice(idx + 2)
        if (handleFrame(frame, handlers)) { finished = true; break }
        idx = buffer.indexOf('\n\n')
      }
    }
    if (buffer.trim() && !finished) handleFrame(buffer, handlers)
  } finally {
    // 主动释放连接，避免代理层连接悬挂；done 事件发出时后端已保存完消息
    if (finished) { try { reader.cancel() } catch (_) { /* ignore */ } }
  }
}

/** 返回 true 表示收到业务结束事件（done/error） */
function handleFrame(frame, handlers) {
  let event = 'message'
  const dataLines = []
  for (const line of frame.split('\n')) {
    if (line.startsWith('event:')) event = line.slice(6).trim()
    else if (line.startsWith('data:')) dataLines.push(line.slice(5).trimStart())
  }
  if (dataLines.length === 0) return false
  const raw = dataLines.join('\n')
  let data = raw
  try { data = JSON.parse(raw) } catch (_) { /* keep raw */ }
  switch (event) {
    case 'references': handlers.onReferences?.(data); break
    case 'delta': handlers.onDelta?.(data); break
    case 'done': handlers.onDone?.(data); return true
    case 'error': handlers.onError?.(data?.error || raw); return true
  }
  return false
}
