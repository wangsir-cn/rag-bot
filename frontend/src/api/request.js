import axios from 'axios'
import { ElMessage } from 'element-plus'

const request = axios.create({
  baseURL: '/api',
  timeout: 60000
})

request.interceptors.request.use((config) => {
  const token = localStorage.getItem('rag_token')
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

request.interceptors.response.use(
  (response) => response.data,
  (error) => {
    const status = error.response?.status
    const message = error.response?.data?.error || error.message || '请求失败'
    if (status === 401) {
      localStorage.removeItem('rag_token')
      localStorage.removeItem('rag_user')
      // 避免在登录页重复跳转
      if (window.location.pathname !== '/login') {
        ElMessage.error('登录已过期，请重新登录')
        window.location.href = '/login'
      }
    } else {
      ElMessage.error(message)
    }
    return Promise.reject(new Error(message))
  }
)

export default request
