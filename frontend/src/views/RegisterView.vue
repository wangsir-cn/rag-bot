<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { apiRegister } from '../api/rag'

const router = useRouter()
const form = ref({ username: '', email: '', password: '', confirm: '' })
const loading = ref(false)

async function handleRegister() {
  if (!form.value.username || !form.value.email || !form.value.password) {
    ElMessage.warning('请填写完整信息')
    return
  }
  if (form.value.password !== form.value.confirm) {
    ElMessage.error('两次密码不一致')
    return
  }
  loading.value = true
  try {
    const res = await apiRegister({
      username: form.value.username, email: form.value.email, password: form.value.password
    })
    localStorage.setItem('rag_token', res.token)
    localStorage.setItem('rag_user', JSON.stringify(res.user))
    ElMessage.success('注册成功')
    router.push('/models')
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
        <h1>注册账号</h1>
        <p>注册后可配置模型、创建知识库</p>
      </div>
      <el-form :model="form" label-position="top" @keyup.enter="handleRegister">
        <el-form-item label="用户名">
          <el-input v-model="form.username" placeholder="请输入用户名" size="large" />
        </el-form-item>
        <el-form-item label="邮箱（用于找回密码）">
          <el-input v-model="form.email" placeholder="请输入邮箱" size="large" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="form.password" type="password" placeholder="至少 6 位" size="large" show-password />
        </el-form-item>
        <el-form-item label="确认密码">
          <el-input v-model="form.confirm" type="password" placeholder="再次输入密码" size="large" show-password />
        </el-form-item>
        <el-button type="primary" size="large" style="width:100%" :loading="loading" @click="handleRegister">
          注册
        </el-button>
      </el-form>
      <div class="auth-links">
        <router-link to="/login">已有账号？返回登录</router-link>
      </div>
    </div>
  </div>
</template>

<style scoped>
.auth-links { display: flex; justify-content: center; margin-top: 16px; font-size: 13px; }
.auth-links a { color: var(--el-color-primary); text-decoration: none; }
</style>
