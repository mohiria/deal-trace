<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import type { FieldRule, ValidatedError } from '@arco-design/web-vue'
import { useAuthStore } from '../stores/auth'
import { ApiError } from '../api/client'

const router = useRouter()
const auth = useAuthStore()

const form = reactive({ email: '', password: '' })
const loading = ref(false)
const errorMessage = ref<string | null>(null)

const rules: Record<string, FieldRule[]> = {
  email: [{ required: true, message: '请输入登录邮箱' }],
  password: [{ required: true, message: '请输入密码' }],
}

async function onSubmit(data: {
  values: Record<string, unknown>
  errors: Record<string, ValidatedError> | undefined
}) {
  // 即时校验失败：Arco 已在表单内展示必填提示，此处不再发请求。
  if (data.errors) {
    return
  }
  errorMessage.value = null
  loading.value = true
  try {
    await auth.login(form.email, form.password)
    await router.push({ name: 'workbench' })
  } catch (error) {
    // 如实展示后端 message（语义由后端给出，前端不自造账号枚举提示）。
    errorMessage.value = error instanceof ApiError ? error.message : '登录失败，请稍后重试'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <div class="login-card">
      <div class="login-brand">
        <span class="login-mark" aria-hidden="true"></span>
        <div>
          <strong>DealTrace</strong>
          <span class="login-subtitle">商迹 CRM 工作台</span>
        </div>
      </div>

      <a-form :model="form" :rules="rules" layout="vertical" @submit="onSubmit">
        <a-form-item field="email" label="登录邮箱">
          <a-input v-model="form.email" placeholder="name@example.com" allow-clear />
        </a-form-item>
        <a-form-item field="password" label="密码">
          <a-input-password v-model="form.password" placeholder="请输入密码" allow-clear />
        </a-form-item>

        <a-alert v-if="errorMessage" type="error" class="login-error">{{ errorMessage }}</a-alert>

        <a-button html-type="submit" type="primary" long :loading="loading" class="login-submit">
          登录
        </a-button>
      </a-form>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--dt-bg, #f6f7fb);
  padding: 24px;
}

.login-card {
  width: 100%;
  max-width: 380px;
  background: var(--dt-surface, #ffffff);
  border: 1px solid var(--dt-line, #e6e8f0);
  border-radius: var(--dt-radius, 12px);
  box-shadow: var(--dt-shadow, 0 16px 40px rgba(36, 42, 66, 0.08));
  padding: 32px 28px;
}

.login-brand {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 24px;
}

.login-mark {
  width: 36px;
  height: 36px;
  border-radius: 10px;
  background: linear-gradient(135deg, var(--dt-brand, #2563ff), var(--dt-green, #0f9f6e));
}

.login-brand strong {
  display: block;
  font-size: 18px;
  font-weight: 800;
  color: var(--dt-text, #202438);
}

.login-subtitle {
  font-size: 12px;
  color: var(--dt-muted, #70778c);
}

.login-error {
  margin-bottom: 16px;
}

.login-submit {
  margin-top: 8px;
}
</style>
