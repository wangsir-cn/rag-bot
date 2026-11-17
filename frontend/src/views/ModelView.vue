<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { apiListModels, apiCreateModel, apiUpdateModel, apiDeleteModel, apiSetDefaultModel, apiTestModel } from '../api/rag'

const models = ref([])
const loading = ref(false)
const dialogOpen = ref(false)
const editing = ref(null)
const form = ref(emptyForm())

function emptyForm() {
  return { name: '', modelType: 'EMBEDDING', baseUrl: '', apiKey: '', modelName: '', dimensions: 1024, isDefault: false }
}

async function load() {
  loading.value = true
  try { models.value = await apiListModels() } finally { loading.value = false }
}
onMounted(load)

function openCreate(type = 'EMBEDDING') {
  editing.value = null
  form.value = { ...emptyForm(), modelType: type }
  dialogOpen.value = true
}

function openEdit(row) {
  editing.value = row.id
  form.value = {
    name: row.name, modelType: row.modelType, baseUrl: row.baseUrl, apiKey: '',
    modelName: row.modelName, dimensions: row.dimensions || 1024, isDefault: row.isDefault
  }
  dialogOpen.value = true
}

async function save() {
  if (!form.value.name || !form.value.baseUrl || !form.value.modelName || (!editing.value && !form.value.apiKey)) {
    ElMessage.warning('请填写名称、Base URL、模型名称' + (editing.value ? '' : ' 和 API Key'))
    return
  }
  if (form.value.baseUrl.trim().endsWith('/v1')) {
    ElMessage.warning('Base URL 不要以 /v1 结尾')
    return
  }
  const payload = { ...form.value }
  if (editing.value && !payload.apiKey) {
    // 编辑时 apiKey 留空表示不修改：先取原值（用掩码不便，交由后端特殊处理）
    delete payload.apiKey
  }
  try {
    if (editing.value) await apiUpdateModel(editing.value, payload)
    else await apiCreateModel(payload)
    ElMessage.success('保存成功')
    dialogOpen.value = false
    load()
  } catch (e) { /* interceptor handles */ }
}

async function setDefault(row) {
  await apiSetDefaultModel(row.id)
  ElMessage.success('已设为默认')
  load()
}

async function remove(row) {
  await ElMessageBox.confirm(`确定删除模型「${row.name}」吗？`, '警告', { type: 'warning' })
  await apiDeleteModel(row.id)
  ElMessage.success('删除成功')
  load()
}

async function test(row) {
  const loadingMsg = ElMessage({ message: '正在测试连接...', type: 'info', duration: 0 })
  try {
    await apiTestModel(row.id)
    ElMessage.success('连接成功')
  } catch (e) { /* handled */ } finally { loadingMsg.close() }
}

const chatModels = () => models.value.filter(m => m.modelType === 'CHAT')
const embModels = () => models.value.filter(m => m.modelType === 'EMBEDDING')
</script>

<template>
  <div>
    <el-alert type="info" show-icon :closable="false" style="margin-bottom:16px"
      title="生成模型与向量模型相互独立，可来自不同服务商。每张卡片需设置一个默认模型；未配置向量模型时无法创建知识库或上传文件。" />

    <div style="display:flex; gap:12px; margin-bottom:16px">
      <el-button type="primary" @click="openCreate('CHAT')">新增生成模型</el-button>
      <el-button type="success" @click="openCreate('EMBEDDING')">新增向量模型</el-button>
    </div>

    <el-row :gutter="16">
      <el-col :span="12">
        <el-card shadow="never">
          <template #header>生成模型 (CHAT) <el-tag size="small" type="warning" effect="plain">{{ chatModels().length }}</el-tag></template>
          <el-table :data="chatModels()" v-loading="loading" size="small">
            <el-table-column prop="name" label="名称" />
            <el-table-column prop="modelName" label="模型ID" />
            <el-table-column label="默认" width="60">
              <template #default="{ row }"><el-tag v-if="row.isDefault" type="success" size="small">默认</el-tag></template>
            </el-table-column>
            <el-table-column label="操作" width="220">
              <template #default="{ row }">
                <el-button link size="small" @click="test(row)">测试</el-button>
                <el-button link size="small" type="primary" @click="openEdit(row)">编辑</el-button>
                <el-button v-if="!row.isDefault" link size="small" @click="setDefault(row)">设默认</el-button>
                <el-button link size="small" type="danger" @click="remove(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
      <el-col :span="12">
        <el-card shadow="never">
          <template #header>向量模型 (EMBEDDING) <el-tag size="small" type="success" effect="plain">{{ embModels().length }}</el-tag></template>
          <el-table :data="embModels()" v-loading="loading" size="small">
            <el-table-column prop="name" label="名称" />
            <el-table-column prop="modelName" label="模型ID" />
            <el-table-column prop="dimensions" label="维度" width="70" />
            <el-table-column label="默认" width="60">
              <template #default="{ row }"><el-tag v-if="row.isDefault" type="success" size="small">默认</el-tag></template>
            </el-table-column>
            <el-table-column label="操作" width="220">
              <template #default="{ row }">
                <el-button link size="small" @click="test(row)">测试</el-button>
                <el-button link size="small" type="primary" @click="openEdit(row)">编辑</el-button>
                <el-button v-if="!row.isDefault" link size="small" @click="setDefault(row)">设默认</el-button>
                <el-button link size="small" type="danger" @click="remove(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
    </el-row>

    <el-dialog v-model="dialogOpen" :title="editing ? '编辑模型' : '新增模型'" width="560px">
      <el-form :model="form" label-width="100px">
        <el-form-item label="展示名称"><el-input v-model="form.name" placeholder="如：通义千问-向量" /></el-form-item>
        <el-form-item label="模型类型">
          <el-radio-group v-model="form.modelType" :disabled="!!editing">
            <el-radio value="CHAT">生成模型 CHAT</el-radio>
            <el-radio value="EMBEDDING">向量模型 EMBEDDING</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="Base URL">
          <el-input v-model="form.baseUrl" placeholder="https://api.xxx.com/compatible-mode（不要带 /v1）" />
        </el-form-item>
        <el-form-item label="API Key">
          <el-input v-model="form.apiKey" type="password" show-password
                    :placeholder="editing ? '留空表示不修改' : '请输入 API Key'" />
        </el-form-item>
        <el-form-item label="模型名称"><el-input v-model="form.modelName" placeholder="实际调用的模型 ID" /></el-form-item>
        <el-form-item v-if="form.modelType === 'EMBEDDING'" label="向量维度">
          <el-input-number v-model="form.dimensions" :min="1024" :max="1024" disabled />
          <span style="margin-left:8px;color:#909399;font-size:12px">系统固定 1024</span>
        </el-form-item>
        <el-form-item label="设为默认"><el-switch v-model="form.isDefault" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogOpen = false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>
