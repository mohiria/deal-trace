<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Message } from '@arco-design/web-vue'
import type { TableColumnData } from '@arco-design/web-vue'
import { useAuthStore } from '../stores/auth'
import { useAccountsStore } from '../stores/accounts'
import { ApiError } from '../api/client'
import type { AccountView } from '../api/accounts'

/**
 * 用户管理（spec：查看账号列表 / 创建 Sales / 启用停用）。仅 Admin 可达（路由 requiresAdmin + 后端 /admin/** 兜底）。
 * 创建角色固定 SALES；自身停用入口收起（D6）；列表 / 状态用 accounts store 承载。
 */
const auth = useAuthStore()
const accounts = useAccountsStore()

const columns: TableColumnData[] = [
  { title: '邮箱', dataIndex: 'email' },
  { title: '姓名', dataIndex: 'name' },
  { title: '角色', slotName: 'role' },
  { title: '状态', slotName: 'status' },
  { title: '创建时间', dataIndex: 'createdAt' },
  { title: '操作', slotName: 'actions' },
]

const roleLabel: Record<string, string> = { ADMIN: '管理员', SALES: '销售' }

/** 自身行不呈现停用入口（D6 防自锁）。 */
function canToggle(record: AccountView): boolean {
  return record.id !== auth.currentUser?.id
}

// ---- 创建 Sales ----
const createVisible = ref(false)
const form = ref({ email: '', name: '', password: '' })
const creating = ref(false)

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/

function openCreate() {
  form.value = { email: '', name: '', password: '' }
  createVisible.value = true
}

async function onCreate() {
  // 即时校验（design D5 前置）：邮箱格式 + 姓名 / 密码必填。后端唯一性 / 格式权威兜底。
  if (!EMAIL_RE.test(form.value.email.trim())) {
    Message.warning('请输入正确的邮箱地址')
    return
  }
  if (form.value.name.trim() === '' || form.value.password === '') {
    Message.warning('姓名与初始密码均不可为空')
    return
  }
  creating.value = true
  try {
    await accounts.createSales({
      email: form.value.email.trim(),
      name: form.value.name.trim(),
      password: form.value.password,
    })
    createVisible.value = false
    Message.success('Sales 账号创建成功')
  } catch (error) {
    if (error instanceof ApiError) {
      Message.error(error.message)
    } else {
      Message.error('创建失败，请稍后重试')
    }
  } finally {
    creating.value = false
  }
}

// ---- 启用 / 停用 ----
const togglingId = ref<number | null>(null)

async function onToggle(record: AccountView) {
  const next = record.status === 'ENABLED' ? 'DISABLED' : 'ENABLED'
  togglingId.value = record.id
  try {
    await accounts.setStatus(record.id, next)
  } catch (error) {
    if (error instanceof ApiError) {
      Message.error(error.message)
    } else {
      Message.error('操作失败，请稍后重试')
    }
  } finally {
    togglingId.value = null
  }
}

const rows = computed(() => accounts.accounts)

onMounted(() => {
  void accounts.loadAccounts()
})
</script>

<template>
  <section class="users-page">
    <header class="users-head">
      <div>
        <h2 class="users-title">用户管理</h2>
        <p class="users-sub">创建销售账号，维护启用与停用状态。</p>
      </div>
      <a-button class="create-sales-open" type="primary" @click="openCreate">新建 Sales</a-button>
    </header>

    <a-table
      :data="rows"
      :columns="columns"
      :pagination="false"
      :loading="accounts.loading"
      row-key="id"
      class="users-table"
    >
      <template #role="{ record }">
        <span class="tag" :class="record.role === 'ADMIN' ? 'purple' : 'blue'">
          {{ roleLabel[record.role] ?? record.role }}
        </span>
      </template>
      <template #status="{ record }">
        <a-tag
          :color="record.status === 'ENABLED' ? 'green' : 'gray'"
          :class="record.status === 'ENABLED' ? 'account-status-enabled' : 'account-status-disabled'"
        >
          {{ record.status === 'ENABLED' ? '启用' : '停用' }}
        </a-tag>
      </template>
      <template #actions="{ record }">
        <a-button
          v-if="canToggle(record)"
          class="status-toggle"
          size="small"
          :status="record.status === 'ENABLED' ? 'danger' : 'normal'"
          :loading="togglingId === record.id"
          @click="onToggle(record)"
        >
          {{ record.status === 'ENABLED' ? '停用' : '启用' }}
        </a-button>
        <span v-else class="status-self">—</span>
      </template>
      <template #empty>
        <div class="users-empty">暂无账号</div>
      </template>
    </a-table>

    <!-- 创建 Sales -->
    <a-modal
      v-model:visible="createVisible"
      title="新建 Sales 账号"
      :render-to-body="false"
      :footer="false"
    >
      <a-form :model="form" layout="vertical" @submit.prevent>
        <a-form-item label="邮箱" required>
          <a-input v-model="form.email" class="sales-email" placeholder="登录邮箱（必填）" />
        </a-form-item>
        <a-form-item label="姓名" required>
          <a-input v-model="form.name" class="sales-name" placeholder="姓名（必填）" />
        </a-form-item>
        <a-form-item label="初始密码" required>
          <a-input-password v-model="form.password" class="sales-password" placeholder="初始密码（必填）" />
        </a-form-item>
      </a-form>
      <div class="users-modal-footer">
        <a-button @click="createVisible = false">取消</a-button>
        <a-button class="sales-confirm" type="primary" :loading="creating" @click="onCreate">创建</a-button>
      </div>
    </a-modal>
  </section>
</template>

<style scoped>
.users-page {
  background: var(--dt-surface, #ffffff);
  border: 1px solid var(--dt-line, #e6e8f0);
  border-radius: var(--dt-radius, 12px);
  box-shadow: var(--dt-shadow, 0 16px 40px rgba(36, 42, 66, 0.08));
  padding: 24px;
}

.users-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
}

.users-title {
  margin: 0;
  font-size: 20px;
  font-weight: 850;
  color: var(--dt-text, #202438);
}

.users-sub {
  margin: 6px 0 0;
  color: var(--dt-muted, #70778c);
  font-size: 13px;
}

.users-empty {
  padding: 24px;
  text-align: center;
  color: var(--dt-muted, #70778c);
}

.users-table :deep(.arco-table-th) {
  background: #fbfcff;
  color: var(--dt-muted, #70778c);
  font-size: 12px;
  font-weight: 850;
}

.users-table :deep(.arco-table-td) {
  font-size: 13px;
}

.tag {
  display: inline-flex;
  align-items: center;
  min-height: 24px;
  padding: 0 9px;
  border-radius: 7px;
  font-weight: 800;
  font-size: 12px;
  white-space: nowrap;
}

.tag.blue {
  background: var(--dt-brand-soft, #eaf0ff);
  color: #1d4ed8;
}

.tag.purple {
  background: var(--dt-purple-soft, #f2ecff);
  color: var(--dt-purple, #7c3aed);
}

.users-modal-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 12px;
}
</style>
