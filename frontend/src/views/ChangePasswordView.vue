<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { apiChangePassword } from '../api/rag'

const router = useRouter()
const user = JSON.parse(localStorage.getItem('rag_user') || '{}')
const forced = ref(!!user.mustChangePassword)
const form = ref({ oldPassword: '', newPassword: '', confirm: '' })
const loading = ref(false)

async function submit() {
  if (form.value.newPassword !== form.value.confirm) { ElMessage.error('两次密码不一致'); return }
  loading.value = true
  try {
    await apiChangePassword({ oldPassword: form.value.oldPassword, newPassword: form.value.newPassword })
    const updated = { ...user, mustChangePassword: false }
    localStorage.setItem('rag_user', JSON.stringify(updated))
    ElMessage.success('密码修改成功')
    router.push('/chat')
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
        <h1>修改密码</h1>
        <p>{{ forced ? '首次登录需修改默认密码，保障账号安全' : '更新账号密码' }}</p>
      </div>
      <el-form label-position="top" @keyup.enter="submit">
        <el-form-item label="原密码">
          <el-input v-model="form.oldPassword" type="password" placeholder="请输入原密码" size="large" show-password />
        </el-form-item>
        <el-form-item label="新密码">
          <el-input v-model="form.newPassword" type="password" placeholder="至少 6 位" size="large" show-password />
        </el-form-item>
        <el-form-item label="确认新密码">
          <el-input v-model="form.confirm" type="password" placeholder="再次输入" size="large" show-password />
        </el-form-item>
        <el-button type="primary" size="large" style="width:100%" :loading="loading" @click="submit">确认修改</el-button>
      </el-form>
    </div>
  </div>
</template>

<style scoped>
.auth-links { display: flex; justify-content: center; margin-top: 16px; font-size: 13px; }
</style>
