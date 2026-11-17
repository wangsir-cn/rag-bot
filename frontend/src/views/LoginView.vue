<script setup>
import { ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { apiLogin } from '../api/rag'

const router = useRouter()
const route = useRoute()
const form = ref({ username: '', password: '' })
const loading = ref(false)

async function handleLogin() {
  if (!form.value.username || !form.value.password) {
    ElMessage.warning('请输入用户名和密码')
    return
  }
  loading.value = true
  try {
    const res = await apiLogin(form.value)
    localStorage.setItem('rag_token', res.token)
    localStorage.setItem('rag_user', JSON.stringify(res.user))
    if (res.mustChangePassword) {
      ElMessage.warning('首次登录，请先修改密码')
      router.push('/change-password')
    } else {
      router.push(route.query.redirect || '/chat')
    }
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
        <h1>RAG Bot</h1>
        <p>企业知识库智能问答</p>
      </div>
      <el-form :model="form" label-position="top" @keyup.enter="handleLogin">
        <el-form-item label="用户名">
          <el-input v-model="form.username" placeholder="请输入用户名" size="large" autocomplete="username" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="form.password" type="password" placeholder="请输入密码" size="large"
                    show-password autocomplete="current-password" />
        </el-form-item>
        <el-button type="primary" size="large" style="width:100%" :loading="loading" @click="handleLogin">
          登录
        </el-button>
      </el-form>
      <div class="auth-links">
        <router-link to="/forgot-password">忘记密码？</router-link>
        <span>·</span>
        <router-link to="/register">没有账号？注册</router-link>
      </div>
    </div>
  </div>
</template>

<style scoped>
.auth-links { display: flex; justify-content: center; gap: 8px; margin-top: 16px; font-size: 13px; }
.auth-links a { color: var(--el-color-primary); text-decoration: none; }
</style>
