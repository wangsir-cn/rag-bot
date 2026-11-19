<script setup>
import { computed } from 'vue'

const props = defineProps({
  citation: { type: Object, required: true }
})

// QA 切片：contentPreview 是问题、answer 是答案，需要成对完整展示
const isQa = computed(() => String(props.citation?.chunkType || '').toUpperCase() === 'QA')
const answerText = computed(() => props.citation?.answer || '')
</script>

<template>
  <el-popover placement="top" :width="420" trigger="click">
    <template #reference>
      <div class="citation-card">
        <span class="citation-index">{{ citation.index }}</span>
        <span class="citation-name">{{ citation.documentName }}</span>
        <span v-if="isQa" class="citation-kind">QA</span>
        <div class="citation-meta">
          第 {{ citation.chunkIndex + 1 }} 段 · 相关度 {{ citation.score?.toFixed?.(4) ?? citation.score }}
        </div>
        <template v-if="isQa">
          <div class="qa-line">
            <span class="qa-label qa-label-q">问</span>
            <span class="qa-text">{{ citation.contentPreview }}</span>
          </div>
          <div class="qa-line">
            <span class="qa-label qa-label-a">答</span>
            <span class="qa-text">{{ answerText }}</span>
          </div>
        </template>
        <div v-else class="citation-preview">{{ citation.contentPreview }}</div>
      </div>
    </template>
    <div>
      <div class="citation-name">
        {{ citation.documentName }} · 第 {{ citation.chunkIndex + 1 }} 段
        <span v-if="isQa" class="citation-kind">QA</span>
      </div>
      <template v-if="isQa">
        <div class="qa-line">
          <span class="qa-label qa-label-q">问</span>
          <span class="qa-text">{{ citation.contentPreview }}</span>
        </div>
        <div class="qa-line">
          <span class="qa-label qa-label-a">答</span>
          <span class="qa-text">{{ answerText }}</span>
        </div>
      </template>
      <div v-else class="hit-content">{{ citation.contentPreview }}</div>
    </div>
  </el-popover>
</template>

<style scoped>
.citation-kind {
  font-size: 11px; line-height: 16px; padding: 0 5px; margin-left: 6px;
  color: #0f766e; background: #ccfbf1; border-radius: 4px; font-weight: 600;
}
.qa-line { display: flex; gap: 6px; margin-top: 4px; font-size: 13px; line-height: 1.65; color: #4b5563; }
.qa-label {
  flex: none; width: 18px; height: 18px; border-radius: 4px;
  font-size: 11px; line-height: 18px; text-align: center; font-weight: 600;
}
.qa-label-q { color: #1d4ed8; background: #e0e7ff; }
.qa-label-a { color: #b45309; background: #fef3c7; }
.qa-text { white-space: pre-wrap; word-break: break-word; }
/* 卡片内限制高度并允许滚动，避免长答案把对话列表撑开；点开气泡可看完整内容 */
.citation-card .qa-text { display: block; max-height: 120px; overflow-y: auto; }
</style>
