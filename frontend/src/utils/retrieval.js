// 检索参数默认值（与后端 application.yml 的 rag.retrieval 保持一致）
export const RETRIEVAL_DEFAULTS = {
  topK: 8,
  candidateK: 30,
  similarityThreshold: 0.25,
  fusion: 'RRF',
  rrfK: 60,
  vectorWeight: 0.65,
  bm25Weight: 0.35,
  rerankEnabled: false
}

export function makeRetrievalParams() {
  return { ...RETRIEVAL_DEFAULTS }
}

// 启用自定义时才把参数放进 options，否则后端沿用全局默认
export function buildRetrievalOptions(params, enabled) {
  return enabled ? { ...params } : undefined
}
