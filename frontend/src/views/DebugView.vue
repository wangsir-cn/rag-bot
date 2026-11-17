<script setup>
import { ref, reactive, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { apiListKb, apiRetrieveDebug } from '../api/rag'
import RetrievalParamsPanel from '../components/RetrievalParamsPanel.vue'
import { makeRetrievalParams, buildRetrievalOptions } from '../utils/retrieval'

const router = useRouter()
const question = ref('')
const loading = ref(false)
const result = ref(null)
const kbs = ref([])
const selectedKbId = ref(null)
const params = reactive(makeRetrievalParams())
const useCustom = ref(false)

let autoTimer = null
let runSeq = 0 // 防止并发响应覆盖：只接受最后一次请求的结果

onMounted(async () => {
  kbs.value = await apiListKb()
  if (kbs.value.length) selectedKbId.value = kbs.value[0].id
})
onUnmounted(() => { if (autoTimer) clearTimeout(autoTimer) })

async function run() {
  if (!question.value.trim()) { ElMessage.warning('请输入测试问题'); return }
  if (!selectedKbId.value) { ElMessage.warning('请选择知识库'); return }
  const payload = { question: question.value, knowledgeBaseId: selectedKbId.value }
  const opt = buildRetrievalOptions(params, useCustom.value)
  if (opt) payload.options = opt

  const seq = ++runSeq
  loading.value = true
  try {
    const data = await apiRetrieveDebug(payload)
    if (seq === runSeq) result.value = data // 丢弃过期响应
  } finally {
    if (seq === runSeq) loading.value = false
  }
}

// 参数/开关变化 → 防抖后自动重检（已有结果时），避免"改了参数没生效"
function onParamsChange() {
  if (autoTimer) clearTimeout(autoTimer)
  if (result.value && question.value.trim() && selectedKbId.value) {
    autoTimer = setTimeout(() => run(), 400)
  }
}

function isOverridden(key) {
  const applied = result.value?.applied
  if (!applied) return false
  return applied[key] !== params[key]
}

function gotoChunk(hit) {
  if (!hit.chunkId) return
  router.push({ path: '/knowledge', query: { kbId: selectedKbId.value, chunkId: hit.chunkId } })
}
</script>

<template>
  <div class="page-card">
    <el-card shadow="never">
      <template #header>检索调试面板 <span class="citation-meta">（不调用 LLM，仅展示检索链路）</span></template>

      <!-- 基础输入 -->
      <div style="display:flex;gap:12px;align-items:center;margin-bottom:12px">
        <span class="citation-meta">知识库</span>
        <el-select v-model="selectedKbId" size="small" style="width:220px">
          <el-option v-for="kb in kbs" :key="kb.id" :label="kb.name" :value="kb.id" />
        </el-select>
        <el-tag v-if="!kbs.length" type="warning" size="small" effect="plain">请先在知识库管理中创建知识库</el-tag>
      </div>
      <el-input v-model="question" type="textarea" :rows="2" placeholder="输入测试问题" @keydown.enter.ctrl="run" />

      <!-- 检索参数（公共组件） -->
      <el-collapse class="param-collapse">
        <el-collapse-item name="params">
          <template #title>
            <div class="param-title">
              <span>检索参数</span>
              <el-tag :type="useCustom ? 'success' : 'info'" size="small" effect="plain">
                {{ useCustom ? '自定义' : '默认配置' }}
              </el-tag>
              <span class="citation-meta">（不启用则沿用 application.yml 全局配置）</span>
            </div>
          </template>
          <RetrievalParamsPanel v-model:enabled="useCustom" :params="params" @change="onParamsChange" />
        </el-collapse-item>
      </el-collapse>

      <el-button type="primary" style="margin-top:12px" :loading="loading" @click="run">检索</el-button>

      <!-- 结果 -->
      <template v-if="result">
        <el-divider />
        <div class="debug-stats">
          <el-tag>向量召回 {{ result.vectorCount }}</el-tag>
          <el-tag type="success">BM25 召回 {{ result.bm25Count }}</el-tag>
          <el-tag type="warning">融合 {{ result.fusion }}</el-tag>
          <el-tag type="info">总耗时 {{ result.totalMs }}ms</el-tag>
        </div>

        <!-- 生效参数回显 -->
        <div v-if="result.applied" class="applied-params">
          <span class="citation-meta">本次生效参数：</span>
          <el-tag v-for="(val, key) in result.applied" :key="key" size="small" effect="plain"
                  :type="isOverridden(key) ? 'warning' : 'info'" class="applied-tag">
            {{ key }}={{ val }}
          </el-tag>
        </div>

        <el-table :data="result.hits" size="small" style="margin-top:12px">
          <el-table-column label="排名" type="index" width="60" />
          <el-table-column label="分数" width="90">
            <template #default="{ row }">{{ row.score?.toFixed?.(4) ?? row.score }}</template>
          </el-table-column>
          <el-table-column prop="documentName" label="来源文档" width="160" />
          <el-table-column label="排名来源" width="140">
            <template #default="{ row }">向量#{{ row.vectorRank }} / 全文#{{ row.bm25Rank }}</template>
          </el-table-column>
          <el-table-column prop="content" label="片段内容" min-width="300" show-overflow-tooltip />
          <el-table-column label="操作" width="90">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click="gotoChunk(row)">跳转</el-button>
            </template>
          </el-table-column>
        </el-table>
      </template>
    </el-card>
  </div>
</template>

<style scoped>
.debug-stats { display: flex; gap: 10px; flex-wrap: wrap; }
.param-collapse { margin-top: 12px; }
.param-title { display: flex; align-items: center; gap: 10px; font-size: 13px; font-weight: 600; }
.applied-params {
  display: flex; align-items: center; gap: 6px; flex-wrap: wrap;
  margin-top: 12px; padding: 10px 12px;
  background: #f8f9fb; border-radius: 6px;
}
.applied-tag { font-family: monospace; }
</style>
