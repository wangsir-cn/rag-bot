<script setup>
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { apiResetPassword } from '../api/rag'

const route = useRoute()
const router = useRouter()
const form = ref({ newPassword: '', confirm: '' })
const loading = ref(false)
const token = ref(route.query.token || '')

async function submit() {
  if (!token.value) { ElMessage.error('缺少重置令牌，请从邮件链接进入'); return }
  if (form.value.newPassword !== form.value.confirm) { ElMessage.error('两次密码不一致'); return }
  loading.value = true
  try {
    await apiResetPassword({ token: token.value, newPassword: form.value.newPassword })
    ElMessage.success('密码重置成功，请登录')
    router.push('/login')
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
        <h1>重置密码</h1>
        <p>请设置新密码</p>
      </div>
      <el-form label-position="top" @keyup.enter="submit">
        <el-form-item label="新密码">
          <el-input v-model="form.newPassword" type="password" placeholder="至少 6 位" size="large" show-password />
        </el-form-item>
        <el-form-item label="确认密码">
          <el-input v-model="form.confirm" type="password" placeholder="再次输入" size="large" show-password />
        </el-form-item>
        <el-button type="primary" size="large" style="width:100%" :loading="loading" @click="submit">重置密码</el-button>
      </el-form>
      <div class="auth-links"><router-link to="/login">返回登录</router-link></div>
    </div>
  </div>
</template>

<style scoped>
.auth-links { display: flex; justify-content: center; margin-top: 16px; font-size: 13px; }
.auth-links a { color: var(--el-color-primary); text-decoration: none; }
</style>
