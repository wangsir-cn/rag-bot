<script setup>
import { ref, onMounted, watch, nextTick } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { apiListChunks, apiCreateChunk, apiUpdateChunk, apiDeleteChunk } from '../api/rag'

const props = defineProps({
  kbId: { type: String, required: true },
  documentId: { type: String, default: null },
  jumpChunkId: { type: String, default: null }
})

const chunks = ref([])
const loading = ref(false)
const createDialog = ref(false)
const form = ref(emptyForm())
function emptyForm() { return { chunkType: 'TEXT', content: '', answer: '' } }

async function load() {
  loading.value = true
  try { chunks.value = await apiListChunks(props.kbId, props.documentId) } finally { loading.value = false }
  if (props.jumpChunkId) {
    await nextTick()
    const el = document.getElementById('chunk-' + props.jumpChunkId)
    if (el) {
      el.scrollIntoView({ behavior: 'smooth', block: 'center' })
      el.classList.add('chunk-highlight')
    }
  }
}
onMounted(load)
watch(() => [props.kbId, props.documentId, props.jumpChunkId], load)

function openCreate(type = 'TEXT') {
  form.value = { ...emptyForm(), chunkType: type }
  createDialog.value = true
}

async function save() {
  if (!form.value.content.trim()) { ElMessage.warning('内容不能为空'); return }
  if (form.value.chunkType === 'QA' && !form.value.answer.trim()) { ElMessage.warning('QA 切片必须填写答案'); return }
  try {
    await apiCreateChunk(props.kbId, { ...form.value })
    ElMessage.success('切片已创建')
    createDialog.value = false
    load()
  } catch (e) { /* handled */ }
}

async function editChunk(row) {
  const { value } = await ElMessageBox.prompt('编辑切片内容（QA 回答含问题+答案，请在弹出框用「|||」分隔）', '编辑切片', {
    inputValue: row.chunkType === 'QA' ? row.content + '|||' + (row.answer || '') : row.content,
    inputType: 'textarea'
  }).catch(() => ({ value: null }))
  if (value === null) return
  if (row.chunkType === 'QA') {
    const [q, a] = value.split('|||')
    await apiUpdateChunk(props.kbId, row.id, { content: (q || '').trim(), answer: (a || '').trim() })
  } else {
    await apiUpdateChunk(props.kbId, row.id, { content: value.trim() })
  }
  ElMessage.success('已更新（提示：文本变更后需删除重建切片以更新向量）')
  load()
}

async function remove(row) {
  await ElMessageBox.confirm('确定删除此切片吗？', '警告', { type: 'warning' })
  await apiDeleteChunk(props.kbId, row.id)
  ElMessage.success('删除成功'); load()
}
</script>

<template>
  <div>
    <div style="display:flex;gap:12px;margin-bottom:12px">
      <el-button type="primary" size="small" @click="openCreate('TEXT')">新增文本切片</el-button>
      <el-button type="success" size="small" @click="openCreate('QA')">新增 QA 切片</el-button>
      <el-tag type="info" size="small" effect="plain">同一类型切片归同一文档，TEXT 与 QA 互斥</el-tag>
    </div>
    <div v-loading="loading" class="chunk-list">
      <div v-for="c in chunks" :key="c.id" :id="'chunk-' + c.id" class="chunk-item">
        <div class="chunk-head">
          <el-tag :type="c.chunkType === 'QA' ? 'success' : 'primary'" size="small">{{ c.chunkType }}</el-tag>
          <span class="chunk-index">#{{ c.chunkIndex + 1 }}</span>
          <div class="chunk-ops">
            <el-button link size="small" @click="editChunk(c)">编辑</el-button>
            <el-button link size="small" type="danger" @click="remove(c)">删除</el-button>
          </div>
        </div>
        <template v-if="c.chunkType === 'QA'">
          <div class="chunk-q"><b>问：</b>{{ c.content }}</div>
          <div class="chunk-a"><b>答：</b>{{ c.answer }}</div>
        </template>
        <template v-else>
          <div class="chunk-text">{{ c.content }}</div>
        </template>
      </div>
      <el-empty v-if="!chunks.length" description="暂无切片" :image-size="60" />
    </div>

    <el-dialog v-model="createDialog" :title="form.chunkType === 'QA' ? '新增 QA 切片' : '新增文本切片'" width="560px">
      <el-form :model="form" label-width="70px">
        <el-form-item label="类型">
          <el-radio-group v-model="form.chunkType">
            <el-radio value="TEXT">文本切片</el-radio>
            <el-radio value="QA">QA 切片</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item :label="form.chunkType === 'QA' ? '问题' : '内容'">
          <el-input v-model="form.content" type="textarea" :rows="4" />
        </el-form-item>
        <el-form-item v-if="form.chunkType === 'QA'" label="答案">
          <el-input v-model="form.answer" type="textarea" :rows="4" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDialog = false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.chunk-list { max-height: calc(100vh - 280px); overflow: auto; }
.chunk-item { border: 1px solid #ebeef5; border-radius: 6px; padding: 10px 12px; margin-bottom: 10px; }
.chunk-head { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.chunk-index { color: #909399; font-size: 12px; }
.chunk-ops { margin-left: auto; }
.chunk-text, .chunk-q, .chunk-a { font-size: 13px; line-height: 1.7; white-space: pre-wrap; }
.chunk-q { color: #303133; }
.chunk-a { color: #606266; }
.chunk-highlight { animation: hl 2s ease; }
@keyframes hl { 0%,60% { background: #fef0f0; border-color: #f56c6c; } 100% { background: transparent; } }
</style>
