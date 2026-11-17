<script setup>
import { ref, onMounted, onUnmounted, watch, nextTick } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { apiListKb, apiCreateKb, apiUpdateKb, apiDeleteKb, apiListModels, apiListDocs,
         apiDeleteDoc, apiReprocessDoc, uploadDocument, downloadQaTemplate } from '../api/rag'
import ChunkPanel from '../components/ChunkPanel.vue'

const route = useRoute()
const kbs = ref([])
const embModels = ref([])
const loading = ref(false)
const selectedKb = ref(null)
const docs = ref([])
const docsLoading = ref(false)
const activeTab = ref('docs')

const kbDialog = ref(false)
const kbEditing = ref(null)
const kbSaving = ref(false)
const kbForm = ref(emptyKbForm())
function emptyKbForm() {
  return { name: '', description: '', embeddingModelId: null, chunkStrategy: 'SEMANTIC', chunkSize: 800, chunkOverlap: 120 }
}

const uploadDialog = ref(false)
const uploadKind = ref('TEXT')
const uploadFile = ref(null)
const uploading = ref(false)
const progress = ref(0)
const jumpChunkId = ref(null)

async function loadKb() {
  loading.value = true
  try {
    const [kbList, models] = await Promise.all([apiListKb(), apiListModels()])
    kbs.value = kbList
    embModels.value = models.filter(m => m.modelType === 'EMBEDDING')
  } finally { loading.value = false }
  const qKb = route.query.kbId
  if (qKb) {
    const target = kbs.value.find(k => k.id === qKb)
    if (target) { selectedKb.value = target; jumpChunkId.value = route.query.chunkId || null; activeTab.value = 'chunks' }
  }
}
onMounted(loadKb)

watch(selectedKb, (kb) => {
  stopPolling()
  // 切换知识库时清空旧的文档筛选与跳转高亮，避免用旧 documentId 查新库的切片
  selectedDocFilter.value = null
  jumpChunkId.value = null
  if (kb) loadDocs().then(() => { if (hasProcessing()) startPolling() })
})

async function loadDocs() {
  if (!selectedKb.value) return
  docsLoading.value = true
  try { docs.value = await apiListDocs(selectedKb.value.id) } finally { docsLoading.value = false }
}

// 异步处理状态轮询：文档处理是后台线程，上传后需持续刷新直到所有文档脱离处理中状态
const PROCESSING_STATUSES = ['PENDING', 'PARSING', 'CHUNKING', 'EMBEDDING']
let pollTimer = null
function hasProcessing() {
  return docs.value.some(d => PROCESSING_STATUSES.includes(d.status))
}
function startPolling() {
  stopPolling()
  pollTimer = setInterval(async () => {
    if (!selectedKb.value || !hasProcessing()) { stopPolling(); return }
    try {
      docs.value = await apiListDocs(selectedKb.value.id)
      if (!hasProcessing()) stopPolling()
    } catch (e) { stopPolling() }
  }, 1500)
}
function stopPolling() {
  if (pollTimer) { clearInterval(pollTimer); pollTimer = null }
}
onUnmounted(stopPolling)

function openCreateKb() {
  if (embModels.value.length === 0) {
    ElMessage.warning('请先到「模型配置」添加向量模型，才能创建知识库'); return
  }
  kbEditing.value = null
  kbForm.value = { ...emptyKbForm(), embeddingModelId: embModels.value.find(m => m.isDefault)?.id || embModels.value[0]?.id }
  kbDialog.value = true
}

function openEditKb(kb) {
  kbEditing.value = kb.id
  kbForm.value = {
    name: kb.name, description: kb.description || '', embeddingModelId: kb.embeddingModelId,
    chunkStrategy: kb.chunkStrategy, chunkSize: kb.chunkSize, chunkOverlap: kb.chunkOverlap
  }
  kbDialog.value = true
}

async function saveKb() {
  if (kbSaving.value) return
  if (!kbForm.value.name || !kbForm.value.embeddingModelId) { ElMessage.warning('请填写名称并选择向量模型'); return }
  const payload = { ...kbForm.value }
  kbSaving.value = true
  try {
    if (kbEditing.value) await apiUpdateKb(kbEditing.value, payload)
    else await apiCreateKb(payload)
    ElMessage.success('保存成功')
    kbDialog.value = false
    await loadKb()
    if (kbEditing.value) selectedKb.value = kbs.value.find(k => k.id === kbEditing.value) || selectedKb.value
  } catch (e) { /* handled */ }
  finally { kbSaving.value = false }
}

async function removeKb(kb) {
  await ElMessageBox.confirm(`确定删除知识库「${kb.name}」吗？其中所有文档与切片都会被删除。`, '危险操作', { type: 'warning' })
  await apiDeleteKb(kb.id)
  ElMessage.success('删除成功')
  if (selectedKb.value?.id === kb.id) selectedKb.value = null
  loadKb()
}

function openUpload(kind) {
  uploadKind.value = kind; uploadFile.value = null; progress.value = 0; uploadDialog.value = true
}

async function doUpload() {
  if (!uploadFile.value) { ElMessage.warning('请选择文件'); return }
  uploading.value = true; progress.value = 0
  try {
    await uploadDocument(selectedKb.value.id, uploadFile.value, uploadKind.value, (p) => progress.value = p)
    ElMessage.success('上传成功，正在后台解析')
    uploadDialog.value = false
    await loadDocs()
    startPolling()
  } finally { uploading.value = false }
}

async function removeDoc(doc) {
  await ElMessageBox.confirm(`确定删除文档「${doc.originalFilename}」吗？`, '警告', { type: 'warning' })
  await apiDeleteDoc(selectedKb.value.id, doc.id)
  ElMessage.success('删除成功'); loadDocs()
}

async function reprocess(doc) {
  await apiReprocessDoc(selectedKb.value.id, doc.id)
  ElMessage.success('已重新加入处理队列')
  await loadDocs()
  startPolling()
}

function statusText(status) {
  return { READY: '就绪', FAILED: '失败', PENDING: '等待中', PARSING: '解析中', CHUNKING: '切片中', EMBEDDING: '向量化中' }[status] || status
}

const selectedDocFilter = ref(null)
function viewDocChunks(doc) {
  selectedDocFilter.value = doc.id; activeTab.value = 'chunks'; jumpChunkId.value = null
}
</script>

<template>
  <div class="kb-layout">
    <el-card shadow="never" class="kb-list-card">
      <template #header>
        <div style="display:flex;justify-content:space-between;align-items:center">
          <span>知识库</span>
          <el-button type="primary" size="small" @click="openCreateKb">新建</el-button>
        </div>
      </template>
      <div v-loading="loading">
        <div v-for="kb in kbs" :key="kb.id" class="kb-item" :class="{ active: selectedKb?.id === kb.id }" @click="selectedKb = kb">
          <div class="kb-item-name">{{ kb.name }}</div>
          <div class="kb-item-meta">{{ kb.documentCount }} 文档 · {{ kb.chunkCount }} 切片</div>
          <div class="kb-item-actions" @click.stop>
            <el-button link size="small" @click="openEditKb(kb)">编辑</el-button>
            <el-button link size="small" type="danger" @click="removeKb(kb)">删除</el-button>
          </div>
        </div>
        <el-empty v-if="!kbs.length" description="还没有知识库" :image-size="60" />
      </div>
    </el-card>

    <el-card shadow="never" class="kb-detail-card">
      <template #header>
        <span v-if="selectedKb">{{ selectedKb.name }}</span>
        <span v-else>请选择一个知识库</span>
      </template>
      <template v-if="selectedKb">
        <el-tabs v-model="activeTab">
          <el-tab-pane label="文档管理" name="docs">
            <div style="display:flex;gap:12px;margin-bottom:16px;flex-wrap:wrap">
              <el-button type="primary" @click="openUpload('TEXT')">上传文本文件</el-button>
              <el-button type="success" @click="openUpload('QA')">上传 QA 文件</el-button>
              <el-button @click="downloadQaTemplate">下载 QA 模板</el-button>
              <el-tag type="info" size="small" effect="plain">文本：txt/word/pdf · QA：每行“问题,答案”</el-tag>
            </div>
            <el-table :data="docs" v-loading="docsLoading" size="small">
              <el-table-column prop="originalFilename" label="文件名" min-width="180" />
              <el-table-column prop="fileKind" label="类型" width="80">
                <template #default="{ row }">
                  <el-tag :type="row.fileKind === 'QA' ? 'success' : 'primary'" size="small">
                    {{ row.fileKind === 'QA' ? 'QA' : '文本' }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="状态" width="110">
                <template #default="{ row }">
                  <el-tag :type="row.status === 'READY' ? 'success' : row.status === 'FAILED' ? 'danger' : 'warning'" size="small">
                    {{ statusText(row.status) }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="切片数" width="90">
                <template #default="{ row }"><el-link type="primary" @click="viewDocChunks(row)">{{ row.chunkCount }}</el-link></template>
              </el-table-column>
              <el-table-column label="操作" width="180">
                <template #default="{ row }">
                  <el-button link size="small" @click="viewDocChunks(row)">切片</el-button>
                  <el-button link size="small" @click="reprocess(row)">重处理</el-button>
                  <el-button link size="small" type="danger" @click="removeDoc(row)">删除</el-button>
                </template>
              </el-table-column>
            </el-table>
            <el-empty v-if="!docs.length" description="暂无文档，先上传一个文件吧" />
          </el-tab-pane>
          <el-tab-pane label="切片管理" name="chunks">
            <ChunkPanel :key="selectedKb.id" :kb-id="selectedKb.id" :document-id="selectedDocFilter" :jump-chunk-id="jumpChunkId" />
          </el-tab-pane>
        </el-tabs>
      </template>
      <el-empty v-else description="从左侧选择或新建知识库" />
    </el-card>

    <el-dialog v-model="kbDialog" :title="kbEditing ? '编辑知识库' : '新建知识库'" width="520px"
               :close-on-click-modal="false" :close-on-press-escape="!kbSaving" :show-close="!kbSaving">
      <el-alert v-if="!kbEditing" type="warning" show-icon :closable="false" style="margin-bottom:12px"
        title="知识库必须绑定一个向量模型；绑定后该库内的文档与检索都使用此模型。" />
      <el-form :model="kbForm" label-width="110px">
        <el-form-item label="名称"><el-input v-model="kbForm.name" placeholder="知识库名称" /></el-form-item>
        <el-form-item label="描述"><el-input v-model="kbForm.description" type="textarea" :rows="2" /></el-form-item>
        <el-form-item label="向量模型">
          <el-select v-model="kbForm.embeddingModelId" placeholder="选择向量模型" style="width:100%">
            <el-option v-for="m in embModels" :key="m.id" :label="m.name + ' (' + m.modelName + ')'" :value="m.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="分块策略">
          <el-select v-model="kbForm.chunkStrategy" style="width:100%">
            <el-option label="语义分块（段落/句子）" value="SEMANTIC" />
            <el-option label="固定长度 + 重叠" value="FIXED" />
          </el-select>
        </el-form-item>
        <el-form-item label="分块大小"><el-input-number v-model="kbForm.chunkSize" :min="100" :max="4000" /></el-form-item>
        <el-form-item label="重叠长度"><el-input-number v-model="kbForm.chunkOverlap" :min="0" :max="1000" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button :disabled="kbSaving" @click="kbDialog = false">取消</el-button>
        <el-button type="primary" :loading="kbSaving" @click="saveKb">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="uploadDialog" :title="uploadKind === 'QA' ? '上传 QA 文件' : '上传文本文件'" width="480px">
      <el-alert v-if="uploadKind === 'QA'" type="info" show-icon :closable="false" style="margin-bottom:12px"
        title="QA 文件每行一条“问题,答案”，一行即一个切片。可先下载模板填写。" />
      <el-upload drag :auto-upload="false" :limit="1" :on-change="(f) => uploadFile = f.raw" accept=".txt,.md,.pdf,.doc,.docx,.csv,.tsv">
        <el-icon size="40"><UploadFilled /></el-icon>
        <div>拖拽文件到此处，或 <em>点击选择</em></div>
      </el-upload>
      <el-progress v-if="uploading" :percentage="progress" style="margin-top:12px" />
      <template #footer>
        <el-button @click="uploadDialog = false">取消</el-button>
        <el-button type="primary" :loading="uploading" @click="doUpload">开始上传</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.kb-layout { display: flex; gap: 16px; height: 100%; overflow-y: auto; align-items: flex-start; }
.kb-list-card { width: 260px; flex-shrink: 0; }
.kb-detail-card { flex: 1; min-width: 0; }
.kb-item { padding: 10px 12px; border-radius: 6px; cursor: pointer; margin-bottom: 6px; border: 1px solid transparent; }
.kb-item:hover { background: #f5f7fa; }
.kb-item.active { background: #ecf5ff; border-color: #409eff; }
.kb-item-name { font-weight: 600; font-size: 14px; }
.kb-item-meta { color: #909399; font-size: 12px; margin-top: 4px; }
.kb-item-actions { margin-top: 4px; }
</style>
