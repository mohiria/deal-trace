<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Message } from '@arco-design/web-vue'
import type { TableColumnData } from '@arco-design/web-vue'
import { ApiError } from '../api/client'
import { searchCustomers, createCustomer } from '../api/customers'
import type { CustomerView } from '../api/customers'
import CreateLeadModal from '../components/CreateLeadModal.vue'

/**
 * 客户管理（spec R1 / R2 / R4）：客户列表 / 搜索、创建客户、在选定客户下新建线索（含查重预检）。
 * 列表 / 创建用组件局部状态（design D3，无跨视图联动）；新建线索前必过查重预检（design D5）。
 */
const props = withDefaults(defineProps<{ debounceMs?: number }>(), { debounceMs: 300 })

// ---- 列表与搜索（R1）----
const customers = ref<CustomerView[]>([])
const keyword = ref('')
const loading = ref(false)
const currentPage = ref(1)
const pageSize = 10
let searchTimer: ReturnType<typeof setTimeout> | null = null

const columns: TableColumnData[] = [
  { title: '客户名称', dataIndex: 'name' },
  { title: '统一社会信用代码', dataIndex: 'usci' },
  { title: '创建时间', dataIndex: 'createdAt' },
]

const pagedCustomers = computed(() => {
  const start = (currentPage.value - 1) * pageSize
  return customers.value.slice(start, start + pageSize)
})

async function loadCustomers(kw?: string) {
  loading.value = true
  try {
    customers.value = await searchCustomers(kw)
  } catch (error) {
    if (error instanceof ApiError) {
      Message.error(error.message)
    }
  } finally {
    loading.value = false
  }
}

function onSearchInput() {
  if (searchTimer !== null) {
    clearTimeout(searchTimer)
  }
  searchTimer = setTimeout(() => {
    currentPage.value = 1
    void loadCustomers(keyword.value)
  }, props.debounceMs)
}

// ---- 创建客户（R2）----
const customerVisible = ref(false)
const customerForm = ref({ name: '', usci: '' })
const creatingCustomer = ref(false)

function openCustomerModal() {
  customerForm.value = { name: '', usci: '' }
  customerVisible.value = true
}

async function onCreateCustomer() {
  // 即时校验：trim 后非空（USCI 归一化 / 校验位由后端权威完成，design D8）。
  if (customerForm.value.name.trim() === '' || customerForm.value.usci.trim() === '') {
    Message.warning('客户名称与统一社会信用代码均不可为空')
    return
  }
  creatingCustomer.value = true
  try {
    const created = await createCustomer(customerForm.value.name, customerForm.value.usci)
    customers.value = [created, ...customers.value]
    customerVisible.value = false
    Message.success('客户创建成功')
  } catch (error) {
    if (error instanceof ApiError && error.code === 'DUPLICATE_CUSTOMER') {
      Message.error('客户已存在')
    } else if (error instanceof ApiError) {
      Message.error(error.message)
    } else {
      Message.error('创建失败，请稍后重试')
    }
  } finally {
    creatingCustomer.value = false
  }
}

const leadVisible = ref(false)

function openLeadModal() {
  leadVisible.value = true
}

onMounted(() => {
  void loadCustomers()
})
</script>

<template>
  <section class="customers-page">
    <header class="customers-head">
      <div>
        <h2 class="customers-title">客户管理</h2>
        <p class="customers-sub">维护客户主体，并在客户上下文中创建业务线索。</p>
      </div>
      <div class="customers-actions">
        <a-button class="create-customer-open" type="primary" @click="openCustomerModal">新建客户</a-button>
        <a-button class="create-lead-open" @click="openLeadModal">新建线索</a-button>
      </div>
    </header>

    <div class="customers-toolbar">
      <input
        v-model="keyword"
        class="customer-search"
        type="text"
        placeholder="按客户名称或统一社会信用代码搜索"
        autocomplete="off"
        @input="onSearchInput"
      />
    </div>

    <a-table
      :data="pagedCustomers"
      :columns="columns"
      :pagination="false"
      :loading="loading"
      row-key="id"
      class="customers-table"
    >
      <template #empty>
        <div class="customers-empty">无匹配客户</div>
      </template>
    </a-table>
    <div v-if="customers.length > pageSize" class="pagination-bar" data-test="list-pagination">
      <a-pagination v-model:current="currentPage" :total="customers.length" :page-size="pageSize" show-total />
    </div>

    <!-- 创建客户 -->
    <a-modal
      v-model:visible="customerVisible"
      title="新建客户"
      :render-to-body="false"
      :ok-loading="creatingCustomer"
      @ok="onCreateCustomer"
    >
      <a-form :model="customerForm" layout="vertical" @submit.prevent>
        <a-form-item label="客户名称" required>
          <a-input v-model="customerForm.name" class="customer-name" placeholder="客户名称（必填）" />
        </a-form-item>
        <a-form-item label="统一社会信用代码" required>
          <a-input v-model="customerForm.usci" class="customer-usci" placeholder="18 位统一社会信用代码（必填）" />
        </a-form-item>
      </a-form>
      <template #footer>
        <a-button @click="customerVisible = false">取消</a-button>
        <a-button class="customer-confirm" type="primary" :loading="creatingCustomer" @click="onCreateCustomer">
          创建
        </a-button>
      </template>
    </a-modal>

    <CreateLeadModal v-model:visible="leadVisible" :debounce-ms="props.debounceMs" />
  </section>
</template>

<style scoped>
.customers-page {
  background: var(--dt-surface, #ffffff);
  border: 1px solid var(--dt-line, #e6e8f0);
  border-radius: var(--dt-radius, 12px);
  box-shadow: var(--dt-shadow, 0 16px 40px rgba(36, 42, 66, 0.08));
  padding: 24px;
}

.customers-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 14px;
}

.customers-title {
  margin: 0;
  font-size: 20px;
  font-weight: 850;
  color: var(--dt-text, #202438);
}

.customers-sub {
  margin: 6px 0 0;
  color: var(--dt-muted, #70778c);
  font-size: 13px;
}

.customers-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  justify-content: flex-end;
}

.customers-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  padding: 14px;
  margin-bottom: 0;
  border: 1px solid var(--dt-line, #e6e8f0);
  border-bottom: 0;
  border-radius: var(--dt-radius, 12px) var(--dt-radius, 12px) 0 0;
  background: #fbfcff;
}

.customer-search {
  width: min(420px, 100%);
  box-sizing: border-box;
  height: 34px;
  padding: 0 12px;
  border: 1px solid var(--dt-line, #e6e8f0);
  border-radius: var(--dt-radius-sm, 8px);
  font-size: 13px;
  color: var(--dt-text, #202438);
}

.customer-search:focus {
  outline: none;
  border-color: var(--dt-primary, #3b6cff);
}

.customers-empty {
  padding: 24px;
  text-align: center;
  color: var(--dt-muted, #70778c);
}

.customers-table :deep(.arco-table-th) {
  background: #fbfcff;
  color: var(--dt-muted, #70778c);
  font-size: 12px;
  font-weight: 850;
}

.customers-table :deep(.arco-table-td) {
  font-size: 13px;
}

.pagination-bar {
  display: flex;
  justify-content: flex-end;
  padding: 14px 0 0;
}
</style>
