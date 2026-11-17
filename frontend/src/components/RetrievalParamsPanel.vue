<script setup>
/**
 * 可复用的检索参数面板。
 * props.params 为响应式对象（由父组件持有），组件直接修改其字段；
 * emit('change') 通知父组件参数已变（调试页据此自动重检）。
 */
import { RETRIEVAL_DEFAULTS } from '../utils/retrieval'

const props = defineProps({
  params: { type: Object, required: true },
  enabled: { type: Boolean, default: false }
})
const emit = defineEmits(['update:enabled', 'change'])

function setEnabled(v) {
  emit('update:enabled', v)
  emit('change')
}
function touch() { emit('change') }
function reset() {
  Object.assign(props.params, RETRIEVAL_DEFAULTS)
  emit('update:enabled', false)
  emit('change')
}
</script>

<template>
  <div class="rp-panel">
    <div class="rp-toolbar">
      <el-switch :model-value="enabled" active-text="启用自定义参数" @update:model-value="setEnabled" />
      <el-button link type="primary" size="small" @click="reset">恢复默认</el-button>
    </div>

    <div class="rp-grid" :class="{ disabled: !enabled }">
      <div class="rp-item">
        <label>最终返回条数 (topK)</label>
        <el-input-number :model-value="params.topK" :min="1" :max="50" size="small"
                         :disabled="!enabled" @update:model-value="v => { params.topK = v; touch() }" />
      </div>
      <div class="rp-item">
        <label>每路候选数 (candidateK)</label>
        <el-input-number :model-value="params.candidateK" :min="1" :max="200" size="small"
                         :disabled="!enabled" @update:model-value="v => { params.candidateK = v; touch() }" />
      </div>
      <div class="rp-item">
        <label>相似度阈值</label>
        <el-input-number :model-value="params.similarityThreshold" :min="0" :max="1" :step="0.05" :precision="2" size="small"
                         :disabled="!enabled" @update:model-value="v => { params.similarityThreshold = v; touch() }" />
      </div>
      <div class="rp-item">
        <label>融合策略</label>
        <el-select :model-value="params.fusion" size="small" :disabled="!enabled"
                   @update:model-value="v => { params.fusion = v; touch() }">
          <el-option label="RRF（倒数排名融合）" value="RRF" />
          <el-option label="WEIGHTED（加权分数融合）" value="WEIGHTED" />
        </el-select>
      </div>
      <div class="rp-item">
        <label>RRF 平滑常数 (k)</label>
        <el-input-number :model-value="params.rrfK" :min="1" :max="200" size="small"
                         :disabled="!enabled" @update:model-value="v => { params.rrfK = v; touch() }" />
      </div>
      <div class="rp-item">
        <label>向量权重</label>
        <el-input-number :model-value="params.vectorWeight" :min="0" :max="1" :step="0.05" :precision="2" size="small"
                         :disabled="!enabled" @update:model-value="v => { params.vectorWeight = v; touch() }" />
      </div>
      <div class="rp-item">
        <label>BM25 权重</label>
        <el-input-number :model-value="params.bm25Weight" :min="0" :max="1" :step="0.05" :precision="2" size="small"
                         :disabled="!enabled" @update:model-value="v => { params.bm25Weight = v; touch() }" />
      </div>
      <div class="rp-item">
        <label>启用 Rerank</label>
        <el-switch :model-value="params.rerankEnabled" :disabled="!enabled"
                   @update:model-value="v => { params.rerankEnabled = v; touch() }" />
      </div>
    </div>
    <div class="rp-hint">权重仅在 WEIGHTED 策略下生效；RRF k 仅在 RRF 策略下生效。</div>
  </div>
</template>

<style scoped>
.rp-toolbar { display: flex; align-items: center; gap: 16px; margin-bottom: 14px; }
.rp-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 14px 20px; transition: opacity 0.2s; }
.rp-grid.disabled { opacity: 0.5; }
.rp-item { display: flex; flex-direction: column; gap: 6px; }
.rp-item label { font-size: 12px; color: #606266; }
.rp-item :deep(.el-input-number), .rp-item :deep(.el-select) { width: 100%; }
.rp-hint { margin-top: 12px; font-size: 11px; color: #a8abb2; }
@media (max-width: 900px) { .rp-grid { grid-template-columns: repeat(2, 1fr); } }
</style>
