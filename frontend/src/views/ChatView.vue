<script setup>
import { ref, reactive, nextTick, onMounted, computed } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import CitationCard from '../components/CitationCard.vue'
import RetrievalParamsPanel from '../components/RetrievalParamsPanel.vue'
import { streamChat, apiListKb, apiListSessions, apiGetSession, apiDeleteSession } from '../api/rag'
import { makeRetrievalParams, buildRetrievalOptions } from '../utils/retrieval'

const router = useRouter()
const messages = ref([])
const input = ref('')
const sending = ref(false)
const messagesRef = ref(null)
let controller = null

const kbs = ref([])
const selectedKbId = ref(null)
const sessions = ref([])
const currentSessionId = ref(null)

// 检索参数（资料问答模式下生效）
const params = reactive(makeRetrievalParams())
const useCustom = ref(false)
const paramsPopover = ref(false)

// 只有一条欢迎消息时展示欢迎面板
const isWelcome = computed(() => messages.value.length === 1 && messages.value[0].id === 'welcome')

onMounted(async () => {
  try {
    kbs.value = await apiListKb()
    sessions.value = await apiListSessions()
    if (kbs.value.length) selectedKbId.value = kbs.value[0].id
  } catch (e) { /* handled */ }
})

function newChat() {
  currentSessionId.value = null
  messages.value = [{
    id: 'welcome', role: 'assistant',
    content: '你好，我是 AI 助手。你可以直接提问进行通用对话；如需基于资料回答，请在上方选择一个知识库。', citations: [], streaming: false
  }]
}

async function openSession(sessionId) {
  const detail = await apiGetSession(sessionId)
  currentSessionId.value = sessionId
  selectedKbId.value = detail.session.knowledgeBaseId || null
  messages.value = (detail.messages || []).map((m, i) => ({
    // 后端存的是大写 USER/ASSISTANT，前端统一归一化为小写，否则 .user 样式匹配不上
    id: m.id || i, role: String(m.role || 'USER').toLowerCase(), content: m.content, citations: m.citations || [], streaming: false
  }))
  if (!messages.value.length) newChat()
}

async function removeSession(sessionId) {
  await ElMessageBox.confirm('确定删除这个会话吗？', '警告', { type: 'warning' })
  await apiDeleteSession(sessionId)
  sessions.value = await apiListSessions()
  if (currentSessionId.value === sessionId) newChat()
}

function scrollToBottom() {
  nextTick(() => { if (messagesRef.value) messagesRef.value.scrollTop = messagesRef.value.scrollHeight })
}

async function send(questionText) {
  const question = (questionText ?? input.value).trim()
  if (!question || sending.value) return
  const isNew = !currentSessionId.value || messages.value.length <= 1
  if (isNew && messages.value.length <= 1) messages.value = []

  messages.value.push({ id: Date.now() + '-u', role: 'user', content: question, citations: [], streaming: false })
  messages.value.push({ id: Date.now() + '-a', role: 'assistant', content: '', citations: [], meta: null, streaming: true, error: false })
  const answer = messages.value[messages.value.length - 1]
  input.value = ''
  sending.value = true
  scrollToBottom()

  controller = new AbortController()
  const history = messages.value.slice(0, -2).filter(m => !m.streaming && m.content).slice(-20).map(m => ({ role: m.role, content: m.content }))
  const payload = { question, knowledgeBaseId: selectedKbId.value || undefined, sessionId: currentSessionId.value || undefined, history }
  // 资料问答（选了知识库）且启用自定义时，把检索参数带上
  if (selectedKbId.value) {
    const opt = buildRetrievalOptions(params, useCustom.value)
    if (opt) payload.options = opt
  }

  try {
    await streamChat(payload, {
      signal: controller.signal,
      onReferences: (citations) => { answer.citations = citations || [] },
      onDelta: (token) => { answer.content += typeof token === 'string' ? token : String(token); scrollToBottom() },
      onDone: async (meta) => {
        answer.meta = meta
        if (meta.sessionId) {
          const isNewSession = currentSessionId.value !== meta.sessionId
          currentSessionId.value = meta.sessionId
          if (isNewSession) sessions.value = await apiListSessions()
        }
      },
      onError: (msg) => { answer.error = true; answer.content += `\n\n[生成失败] ${msg}` }
    })
  } catch (e) {
    if (e.name !== 'AbortError') { answer.error = true; answer.content += `\n\n[请求失败] ${e.message}` }
  } finally {
    answer.streaming = false; sending.value = false; controller = null; scrollToBottom()
  }
}

function stop() { controller?.abort() }

function handleKeydown(event) {
  if (event.key === 'Enter' && !event.shiftKey) { event.preventDefault(); send() }
}

/** 引用卡片跳转：到知识库管理页高亮对应切片 */
function gotoChunk(citation) {
  const kbId = citation.knowledgeBaseId || selectedKbId.value
  if (!kbId || !citation.chunkId) { ElMessage.warning('该片段无跳转信息'); return }
  router.push({ path: '/knowledge', query: { kbId, chunkId: citation.chunkId } })
}
</script>

<template>
  <div class="chat-layout">
    <!-- 左侧会话栏 -->
    <aside class="session-sidebar">
      <div class="side-head">
        <div class="side-head-title"><el-icon><ChatDotRound /></el-icon><span>对话记录</span></div>
        <el-tooltip content="新建对话" placement="bottom">
          <el-button class="new-chat-btn" circle size="small" @click="newChat"><el-icon><Plus /></el-icon></el-button>
        </el-tooltip>
      </div>
      <div class="session-list">
        <div v-for="s in sessions" :key="s.id" class="session-item" :class="{ active: currentSessionId === s.id }"
             @click="openSession(s.id)">
          <el-icon class="session-chat-icon"><ChatLineSquare /></el-icon>
          <div class="session-info">
            <div class="session-title">{{ s.title }}</div>
            <div class="session-meta">{{ s.knowledgeBaseName || '通用问答' }}</div>
          </div>
          <el-button text class="session-del" icon="Delete" title="删除会话" @click.stop="removeSession(s.id)" />
        </div>
        <el-empty v-if="!sessions.length" description="暂无历史对话" :image-size="48" />
      </div>
      <div class="side-foot">共 {{ sessions.length }} 个对话</div>
    </aside>

    <!-- 主对话区 -->
    <main class="chat-main">
      <!-- 顶部工具栏 -->
      <header class="chat-toolbar">
        <div class="toolbar-inner">
          <div class="toolbar-left">
            <span class="kb-picker-label"><el-icon><Collection /></el-icon> 知识库</span>
            <el-select v-model="selectedKbId" clearable class="kb-select" placeholder="通用问答（不选知识库）">
              <el-option label="通用问答（不检索资料）" :value="null" />
              <el-option v-for="kb in kbs" :key="kb.id" :label="kb.name" :value="kb.id" />
            </el-select>
          </div>
          <div class="toolbar-right">
            <el-popover v-model:visible="paramsPopover" placement="bottom-end" :width="620" trigger="click">
              <template #reference>
                <el-button class="param-btn" :type="useCustom && selectedKbId ? 'primary' : 'default'" plain size="small"
                           :disabled="!selectedKbId" title="调整检索参数">
                  <el-icon><Setting /></el-icon>
                  <span>检索参数</span>
                  <el-tag v-if="useCustom && selectedKbId" type="success" size="small" effect="plain" class="param-btn-tag">自定义</el-tag>
                </el-button>
              </template>
              <div class="popover-title">
                <span>检索参数</span>
                <span class="citation-meta">仅对资料问答生效，通用对话不使用</span>
              </div>
              <RetrievalParamsPanel v-model:enabled="useCustom" :params="params" />
            </el-popover>
            <div class="mode-badge" :class="selectedKbId ? 'rag' : 'general'">
              <span class="mode-dot"></span>
              {{ selectedKbId ? '资料问答' : '通用对话' }}
            </div>
          </div>
        </div>
      </header>

      <!-- 消息区 -->
      <div ref="messagesRef" class="chat-messages">
        <div class="messages-inner">
          <!-- 欢迎面板 -->
          <div v-if="isWelcome" class="welcome-panel">
            <div class="welcome-logo"><el-icon><ChatDotRound /></el-icon></div>
            <h2>你好，我是 RAG Bot</h2>
            <p>我可以基于你的知识库精准回答，也可以像普通 AI 助手一样与你自由对话。</p>
            <div class="welcome-tips">
              <div class="tip-card" @click="send('介绍一下你的能力')">
                <el-icon class="tip-icon"><MagicStick /></el-icon>
                <div class="tip-title">你能做什么</div>
                <div class="tip-desc">了解我的能力边界</div>
              </div>
              <div class="tip-card" @click="send('如何使用知识库问答？')">
                <el-icon class="tip-icon"><Reading /></el-icon>
                <div class="tip-title">知识库用法</div>
                <div class="tip-desc">从上传文档开始</div>
              </div>
              <div class="tip-card" @click="send('给我一些提问建议')">
                <el-icon class="tip-icon"><Compass /></el-icon>
                <div class="tip-title">提问建议</div>
                <div class="tip-desc">快速上手对话</div>
              </div>
            </div>
          </div>

          <!-- 消息列表 -->
          <div v-for="m in messages" :key="m.id" v-show="!isWelcome || m.id !== 'welcome'"
               class="message-row" :class="m.role">
            <div class="message-avatar">
              <el-icon v-if="m.role === 'user'"><User /></el-icon>
              <el-icon v-else><Bot /></el-icon>
            </div>
            <div class="message-content">
              <div class="message-author">{{ m.role === 'user' ? '我' : 'RAG 助手' }}</div>
              <div class="message-bubble">{{ m.content }}<span v-if="m.streaming" class="streaming-caret"></span></div>
              <div v-if="m.citations?.length" class="citations">
                <div v-for="c in m.citations" :key="c.chunkId" class="citation-with-jump">
                  <CitationCard :citation="c" />
                  <el-button link type="primary" size="small" @click="gotoChunk(c)">跳转片段</el-button>
                </div>
              </div>
              <div v-if="m.meta" class="run-meta">
                <template v-if="m.meta.mode === 'GENERAL'">通用对话 · 未检索知识库</template>
                <template v-else>
                  <div>命中 {{ m.meta.citationCount }} 个片段 · 向量 {{ m.meta.vectorCount }} / 全文 {{ m.meta.bm25Count }} · 耗时 {{ m.meta.totalMs }}ms</div>
                  <div v-if="m.meta.applied && Object.keys(m.meta.applied).length" class="applied-line">
                    <span>生效参数：</span>
                    <span v-for="(val, key) in m.meta.applied" :key="key" class="applied-chip">{{ key }}={{ val }}</span>
                  </div>
                </template>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- 输入区 -->
      <footer class="chat-composer">
        <div class="composer-inner">
          <div class="composer-box">
            <el-input v-model="input" type="textarea" :autosize="{ minRows: 1, maxRows: 6 }"
                      resize="none" placeholder="输入问题，Enter 发送，Shift + Enter 换行"
                      :disabled="sending" @keydown="handleKeydown" />
            <el-button v-if="sending" class="send-btn stop" circle title="停止生成" @click="stop">
              <el-icon><CloseBold /></el-icon>
            </el-button>
            <el-button v-else class="send-btn" circle title="发送" :disabled="!input.trim()" @click="send()">
              <el-icon><Promotion /></el-icon>
            </el-button>
          </div>
          <div class="composer-hint">
            {{ selectedKbId ? '回答基于所选知识库生成 · 引用可点击跳转到知识库切片' : '通用对话模式 · 直接提问即可，模型将基于自身知识回答' }}
          </div>
        </div>
      </footer>
    </main>
  </div>
</template>

<style scoped>
.chat-layout { --chat-width: 1180px; display: flex; height: 100%; background: var(--rag-bg); }

/* ---------- 会话侧栏 ---------- */
.session-sidebar { width: 250px; flex-shrink: 0; background: #fff; border-right: 1px solid var(--rag-border); display: flex; flex-direction: column; }
.side-head { display: flex; align-items: center; justify-content: space-between; padding: 18px 16px 12px; }
.side-head-title { display: flex; align-items: center; gap: 8px; font-size: 14px; font-weight: 600; color: #303133; }
.side-head-title .el-icon { font-size: 17px; color: var(--rag-primary); }
.new-chat-btn { color: var(--rag-primary); border-color: #d9ecff; background: #ecf5ff; }
.new-chat-btn:hover { background: var(--rag-primary); color: #fff; border-color: var(--rag-primary); }
.session-list { flex: 1; overflow-y: auto; padding: 4px 8px; }
.session-item { position: relative; display: flex; align-items: center; gap: 10px; padding: 10px; border-radius: 10px; cursor: pointer; margin-bottom: 4px; transition: background .15s; }
.session-item:hover { background: #f5f7fa; }
.session-item.active { background: #eaf3ff; }
.session-chat-icon { font-size: 16px; color: #a8abb2; flex-shrink: 0; }
.session-item.active .session-chat-icon { color: var(--rag-primary); }
.session-info { min-width: 0; flex: 1; }
.session-title { font-size: 13px; font-weight: 600; color: #303133; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; padding-right: 20px; }
.session-meta { font-size: 11px; color: #a8abb2; margin-top: 3px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.session-del { position: absolute; right: 5px; top: 8px; padding: 5px; height: 26px; color: #a8abb2; opacity: 0; transition: opacity .15s, color .15s, background-color .15s; }
.session-item:hover .session-del, .session-item.active .session-del { opacity: 1; }
.session-del:hover { color: #f56c6c; background: #fef0f0; }
.side-foot { padding: 12px 16px; font-size: 11px; color: #c0c4cc; border-top: 1px solid var(--rag-border); text-align: center; }

/* ---------- 主对话区 ---------- */
.chat-main { flex: 1; min-width: 0; display: flex; flex-direction: column; }

/* 顶部工具栏 */
.chat-toolbar { height: 58px; flex-shrink: 0; background: #fff; border-bottom: 1px solid var(--rag-border); display: flex; align-items: center; }
.toolbar-inner { width: 100%; max-width: var(--chat-width); margin: 0 auto; padding: 0 24px; display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.toolbar-left { display: flex; align-items: center; gap: 10px; }
.toolbar-right { display: flex; align-items: center; gap: 10px; }
.kb-picker-label { display: flex; align-items: center; gap: 5px; font-size: 13px; color: #606266; white-space: nowrap; }
.kb-picker-label .el-icon { font-size: 15px; color: #909399; }
.kb-select { width: 280px; }
.param-btn { display: inline-flex; align-items: center; gap: 5px; }
.param-btn-tag { margin-left: 2px; }
.popover-title { display: flex; align-items: baseline; gap: 10px; font-size: 13px; font-weight: 600; color: #303133; margin-bottom: 16px; }
.mode-badge { display: inline-flex; align-items: center; gap: 6px; padding: 5px 12px; border-radius: 20px; font-size: 12px; font-weight: 500; white-space: nowrap; }
.mode-badge.rag { color: #67c23a; background: #f0f9eb; }
.mode-badge.general { color: #409eff; background: #ecf5ff; }
.mode-dot { width: 6px; height: 6px; border-radius: 50%; background: currentColor; }

/* 消息区 */
.chat-messages { flex: 1; overflow-y: auto; padding: 28px 24px; }
.messages-inner { max-width: var(--chat-width); margin: 0 auto; }
.message-row { display: flex; gap: 12px; margin-bottom: 26px; align-items: flex-start; }
.message-row.user { flex-direction: row-reverse; }
.message-avatar { width: 36px; height: 36px; border-radius: 10px; display: flex; align-items: center; justify-content: center; flex-shrink: 0; background: #eaf3ff; color: var(--rag-primary); font-size: 18px; }
.message-row.user .message-avatar { background: linear-gradient(135deg, #409eff, #66b1ff); color: #fff; }
.message-content { max-width: min(880px, calc(100% - 60px)); min-width: 0; }
.message-author { font-size: 12px; color: #909399; margin: 0 0 6px 2px; }
.message-row.user .message-author { text-align: right; margin-right: 2px; }
.message-bubble { background: #fff; border: 1px solid var(--rag-border); border-radius: 4px 14px 14px 14px; padding: 14px 16px; line-height: 1.8; font-size: 14px; white-space: pre-wrap; word-break: break-word; box-shadow: 0 2px 10px rgba(0, 0, 0, 0.03); }
.message-row.user .message-bubble { background: linear-gradient(135deg, #409eff, #66b1ff); border-color: transparent; color: #fff; border-radius: 14px 4px 14px 14px; }
.streaming-caret { display: inline-block; width: 7px; height: 15px; background: var(--rag-primary); vertical-align: middle; margin-left: 3px; animation: blink 0.8s infinite; }
@keyframes blink { 50% { opacity: 0; } }
.citation-with-jump { display: flex; align-items: center; gap: 8px; }
.run-meta { font-size: 11px; color: #a8abb2; margin-top: 8px; }
.applied-line { margin-top: 6px; display: flex; flex-wrap: wrap; gap: 5px; align-items: center; }
.applied-chip { font-family: monospace; background: #f4f4f5; color: #909399; padding: 2px 6px; border-radius: 4px; }

/* 欢迎面板 */
.welcome-panel { max-width: 660px; margin: 6vh auto 0; text-align: center; }
.welcome-logo { width: 64px; height: 64px; border-radius: 18px; background: linear-gradient(135deg, #409eff, #67c23a); color: #fff; display: flex; align-items: center; justify-content: center; margin: 0 auto 20px; font-size: 32px; box-shadow: 0 8px 24px rgba(64, 158, 255, 0.28); }
.welcome-panel h2 { font-size: 24px; margin: 0 0 10px; color: #303133; }
.welcome-panel > p { color: #909399; font-size: 14px; line-height: 1.7; margin: 0 0 32px; }
.welcome-tips { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; }
.tip-card { background: #fff; border: 1px solid var(--rag-border); border-radius: 12px; padding: 16px; text-align: left; cursor: pointer; transition: border-color .2s, transform .2s, box-shadow .2s; }
.tip-card:hover { border-color: var(--rag-primary); transform: translateY(-2px); box-shadow: 0 6px 18px rgba(64, 158, 255, 0.12); }
.tip-icon { font-size: 20px; color: var(--rag-primary); }
.tip-title { font-size: 13px; font-weight: 600; color: #303133; margin-top: 10px; }
.tip-desc { font-size: 11px; color: #a8abb2; margin-top: 4px; }

/* 输入区 */
.chat-composer { flex-shrink: 0; padding: 8px 24px 18px; background: linear-gradient(to top, var(--rag-bg) 65%, rgba(245, 247, 250, 0)); }
.composer-inner { max-width: var(--chat-width); margin: 0 auto; }
.composer-box { display: flex; align-items: flex-end; gap: 12px; background: #fff; border: 1px solid var(--rag-border); border-radius: 16px; padding: 10px 10px 10px 18px; box-shadow: 0 4px 20px rgba(0, 0, 0, 0.05); transition: border-color .2s, box-shadow .2s; }
.composer-box:focus-within { border-color: var(--rag-primary); box-shadow: 0 4px 20px rgba(64, 158, 255, 0.12); }
.composer-box :deep(.el-textarea__inner) { box-shadow: none !important; padding: 9px 0; line-height: 1.6; }
.send-btn { width: 40px; height: 40px; flex-shrink: 0; background: var(--rag-primary); border-color: var(--rag-primary); color: #fff; font-size: 17px; }
.send-btn:hover { background: #66b1ff; border-color: #66b1ff; }
.send-btn.stop { background: #f56c6c; border-color: #f56c6c; }
.send-btn.stop:hover { background: #f78989; border-color: #f78989; }
.send-btn.is-disabled { background: #c0c4cc; border-color: #c0c4cc; color: #fff; }
.composer-hint { text-align: center; font-size: 11px; color: #c0c4cc; margin-top: 10px; }

/* 引用卡片（组件无 scoped，这里补充跳转行间距） */
.citation-with-jump { margin-bottom: 8px; }
</style>
