<script setup>
defineProps({
  citation: { type: Object, required: true }
})
</script>

<template>
  <el-popover placement="top" :width="360" trigger="click">
    <template #reference>
      <div class="citation-card">
        <span class="citation-index">{{ citation.index }}</span>
        <span class="citation-name">{{ citation.documentName }}</span>
        <div class="citation-meta">
          第 {{ citation.chunkIndex + 1 }} 段 · 相关度 {{ citation.score?.toFixed?.(4) ?? citation.score }}
        </div>
        <div class="citation-preview">{{ citation.contentPreview }}</div>
      </div>
    </template>
    <div>
      <div class="citation-name">{{ citation.documentName }} · 第 {{ citation.chunkIndex + 1 }} 段</div>
      <div class="hit-content">{{ citation.contentPreview }}</div>
    </div>
  </el-popover>
</template>
