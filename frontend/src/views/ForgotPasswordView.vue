<script setup>
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { apiForgotPassword } from '../api/rag'

const email = ref('')
const loading = ref(false)
const sent = ref(false)

async function submit() {
  if (!email.value) { ElMessage.warning('请输入注册邮箱'); return }
  loading.value = true
  try {
    const res = await apiForgotPassword(email.value)
    sent.value = true
    ElMessage.success(res.message || '重置链接已发送')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="auth-page">
    <div class="auth-card">
      <div class="auth-brand">
        <div class="brand-logo">R</div>
        <h1>忘记密码</h1>
        <p>输入注册邮箱，接收重置链接</p>
      </div>
      <template v-if="!sent">
        <el-form label-position="top" @keyup.enter="submit">
          <el-form-item label="注册邮箱">
            <el-input v-model="email" placeholder="请输入注册邮箱" size="large" />
          </el-form-item>
          <el-button type="primary" size="large" style="width:100%" :loading="loading" @click="submit">发送重置链接</el-button>
        </el-form>
      </template>
      <template v-else>
        <el-result icon="success" title="邮件已发送" sub-title="请查收邮箱，点击链接重置密码（30 分钟内有效）" />
      </template>
      <div class="auth-links">
        <router-link to="/login">返回登录</router-link>
      </div>
    </div>
  </div>
</template>

<style scoped>
.auth-links { display: flex; justify-content: center; margin-top: 16px; font-size: 13px; }
.auth-links a { color: var(--el-color-primary); text-decoration: none; }
</style>
