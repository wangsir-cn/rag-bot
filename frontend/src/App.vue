<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox, ElMessage } from 'element-plus'

const route = useRoute()
const router = useRouter()
const currentTitle = computed(() => route.meta.title || 'RAG Bot')
const user = computed(() => JSON.parse(localStorage.getItem('rag_user') || '{}'))
const isAuthPage = computed(() => ['/login', '/register', '/forgot-password', '/reset-password'].includes(route.path))

function logout() {
  localStorage.removeItem('rag_token')
  localStorage.removeItem('rag_user')
  ElMessage.success('已退出登录')
  router.push('/login')
}

async function changePassword() {
  router.push('/change-password')
}
</script>

<template>
  <div v-if="isAuthPage" class="auth-root">
    <router-view />
  </div>
  <el-container v-else class="app-shell">
    <el-aside width="230px" class="app-aside">
      <div class="brand">
        <div class="brand-logo">R</div>
        <div>
          <div class="brand-title">RAG Bot</div>
          <div class="brand-sub">企业知识库问答</div>
        </div>
      </div>
      <el-menu :default-active="route.path" router class="app-menu">
        <el-menu-item index="/chat">
          <el-icon><ChatDotRound /></el-icon>
          <span>智能问答</span>
        </el-menu-item>
        <el-menu-item index="/knowledge">
          <el-icon><FolderOpened /></el-icon>
          <span>知识库管理</span>
        </el-menu-item>
        <el-menu-item index="/models">
          <el-icon><Cpu /></el-icon>
          <span>模型配置</span>
        </el-menu-item>
        <el-menu-item index="/debug">
          <el-icon><Search /></el-icon>
          <span>检索调试</span>
        </el-menu-item>
      </el-menu>
      <div class="aside-footer">Spring AI · pgvector</div>
    </el-aside>

    <el-container>
      <el-header class="app-header">
        <span class="header-title">{{ currentTitle }}</span>
        <div class="header-user">
          <el-tag type="info" size="small" effect="plain">{{ user.role === 'ADMIN' ? '管理员' : '用户' }}</el-tag>
          <span class="username">{{ user.username }}</span>
          <el-dropdown @command="(cmd) => cmd === 'logout' ? logout() : changePassword()">
            <el-button size="small" circle><el-icon><User /></el-icon></el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="changePwd">修改密码</el-dropdown-item>
                <el-dropdown-item command="logout" divided>退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>
      <el-main class="app-main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.header-user { display: flex; align-items: center; gap: 10px; }
.username { font-size: 14px; color: #303133; }
</style>
