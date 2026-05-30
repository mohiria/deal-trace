<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { Message } from '@arco-design/web-vue'
import type { TableColumnData } from '@arco-design/web-vue'
import { useAuthStore } from '../stores/auth'
import { ApiError } from '../api/client'
import { searchCustomers, createCustomer } from '../api/customers'
import type { CustomerView } from '../api/customers'
import { createLead, duplicateCheck } from '../api/leads'
import type { DuplicateCheckResult } from '../api/leads'
import { BUSINESS_TYPES, isValidContactPhone } from '../utils/lead'
import CustomerSelect from '../components/CustomerSelect.vue'

/**
 * 客户管理（spec R1 / R2 / R4）：客户列表 / 搜索、创建客户、在选定客户下新建线索（含查重预检）。
 * 列表 / 创建用组件局部状态（design D3，无跨视图联动）；新建线索前必过查重预检（design D5）。
 */
const props = withDefaults(defineProps<{ debounceMs?: number }>(), { debounceMs: 300 })

const auth = useAuthStore()

// ---- 列表与搜索（R1）----
const customers = ref<CustomerView[]>([])
const keyword = ref('')
const loading = ref(false)
let searchTimer: ReturnType<typeof setTimeout> | null = null

const columns: TableColumnData[] = [
  { title: '客户名称', dataIndex: 'name' },
  { title: '统一社会信用代码', dataIndex: 'usci' },
  { title: '创建时间', dataIndex: 'createdAt' },
]

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

// ---- 新建线索 + 查重预检（R4）----
const leadVisible = ref(false)
const selectedCustomerId = ref<number | null>(null)
const selectedCustomer = ref<CustomerView | null>(null)
const leadForm = ref({ businessType: '', contactName: '', contactPhone: '', leadSource: '' })
const ownerMode = ref<'self' | 'pool'>('self')
const dupResult = ref<DuplicateCheckResult | null>(null)
const submittingLead = ref(false)

function openLeadModal() {
  selectedCustomerId.value = null
  selectedCustomer.value = null
  leadForm.value = { businessType: '', contactName: '', contactPhone: '', leadSource: '' }
  ownerMode.value = 'self'
  dupResult.value = null
  leadVisible.value = true
}

function onCustomerSelected(customer: CustomerView) {
  selectedCustomer.value = customer
}

/** 客户 + 业务类型齐备即触发查重预检（design D5）。 */
watch(
  () => [selectedCustomerId.value, leadForm.value.businessType] as const,
  async ([id, type]) => {
    if (id == null || !type) {
      dupResult.value = null
      return
    }
    try {
      dupResult.value = await duplicateCheck(id, type)
    } catch (error) {
      dupResult.value = null
      if (error instanceof ApiError) {
        Message.error(error.message)
      }
    }
  },
)

function blockingMessage(reason: string | null): string {
  if (reason === 'DUPLICATE_ACTIVE_LEAD') {
    return '该客户在本年度该业务类型下已有进行中线索，不可重复创建。'
  }
  if (reason === 'DUPLICATE_WON_LEAD') {
    return '该客户在本年度该业务类型下已有已赢单线索，不可重复创建。'
  }
  return '该业务线索不可重复创建。'
}

async function onCreateLead() {
  // 即时校验（design D7）：客户 / 业务类型 / 联系人 / 联系电话格式。
  if (selectedCustomerId.value == null) {
    Message.warning('请先选择客户')
    return
  }
  if (!leadForm.value.businessType) {
    Message.warning('请选择业务类型')
    return
  }
  if (leadForm.value.contactName.trim() === '') {
    Message.warning('请填写联系人')
    return
  }
  if (!isValidContactPhone(leadForm.value.contactPhone)) {
    Message.warning('联系电话格式不正确')
    return
  }
  // 预检阻塞：不发创建请求。
  if (dupResult.value && dupResult.value.canCreate === false) {
    Message.warning(blockingMessage(dupResult.value.blockingReason))
    return
  }

  submittingLead.value = true
  try {
    const source = leadForm.value.leadSource.trim()
    await createLead({
      customerId: selectedCustomerId.value,
      businessType: leadForm.value.businessType,
      contactName: leadForm.value.contactName.trim(),
      contactPhone: leadForm.value.contactPhone.trim(),
      ...(source ? { leadSource: source } : {}),
      ...(!auth.isAdmin && ownerMode.value === 'pool' ? { assignToPool: true } : {}),
    })
    leadVisible.value = false
    Message.success('线索创建成功')
  } catch (error) {
    if (error instanceof ApiError) {
      Message.error(error.message)
    } else {
      Message.error('创建失败，请稍后重试')
    }
  } finally {
    submittingLead.value = false
  }
}

onMounted(() => {
  void loadCustomers()
})
</script>

<template>
  <section class="customers-page">
    <header class="customers-head">
      <h2 class="customers-title">客户管理</h2>
      <div class="customers-actions">
        <a-button class="create-customer-open" type="primary" @click="openCustomerModal">新建客户</a-button>
        <a-button class="create-lead-open" @click="openLeadModal">新建线索</a-button>
      </div>
    </header>

    <input
      v-model="keyword"
      class="customer-search"
      type="text"
      placeholder="按客户名称或统一社会信用代码搜索"
      autocomplete="off"
      @input="onSearchInput"
    />

    <a-table
      :data="customers"
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

    <!-- 新建线索 -->
    <a-modal
      v-model:visible="leadVisible"
      title="新建业务线索"
      :render-to-body="false"
      :footer="false"
    >
      <a-form :model="leadForm" layout="vertical" @submit.prevent>
        <a-form-item label="客户" required>
          <CustomerSelect
            v-model="selectedCustomerId"
            :debounce-ms="props.debounceMs"
            @select="onCustomerSelected"
          />
        </a-form-item>
        <a-form-item label="业务类型" required>
          <a-radio-group v-model="leadForm.businessType" class="lead-type">
            <a-radio v-for="t in BUSINESS_TYPES" :key="t" :value="t">{{ t }}</a-radio>
          </a-radio-group>
        </a-form-item>

        <div v-if="dupResult && dupResult.canCreate === false" class="lead-block">
          {{ blockingMessage(dupResult.blockingReason) }}
        </div>

        <div v-if="dupResult && dupResult.historicalLost.length > 0" class="historical-lost">
          <p class="historical-lost-title">该客户该业务类型历史流失记录：</p>
          <ul>
            <li v-for="(h, i) in dupResult.historicalLost" :key="i" class="historical-lost-item">
              <span class="hl-time">{{ h.lostAt }}</span>
              <span class="hl-reason">{{ h.loseReason }}</span>
              <span v-if="h.loseNote" class="hl-note">{{ h.loseNote }}</span>
            </li>
          </ul>
        </div>

        <a-form-item label="联系人" required>
          <a-input v-model="leadForm.contactName" class="lead-contact-name" placeholder="联系人姓名（必填）" />
        </a-form-item>
        <a-form-item label="联系电话" required>
          <a-input
            v-model="leadForm.contactPhone"
            class="lead-contact-phone"
            placeholder="手机号或座机（必填）"
          />
        </a-form-item>
        <a-form-item label="线索来源">
          <a-input v-model="leadForm.leadSource" class="lead-source" placeholder="线索来源（选填）" />
        </a-form-item>
        <a-form-item v-if="!auth.isAdmin" label="归属">
          <a-radio-group v-model="ownerMode" class="lead-owner">
            <a-radio value="self">归属自己</a-radio>
            <a-radio value="pool">放入公海</a-radio>
          </a-radio-group>
        </a-form-item>
      </a-form>
      <div class="lead-footer">
        <a-button @click="leadVisible = false">取消</a-button>
        <a-button class="lead-confirm" type="primary" :loading="submittingLead" @click="onCreateLead">
          创建线索
        </a-button>
      </div>
    </a-modal>
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
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}

.customers-title {
  margin: 0;
  font-size: 20px;
  font-weight: 700;
  color: var(--dt-text, #202438);
}

.customers-actions {
  display: flex;
  gap: 8px;
}

.customer-search {
  width: 100%;
  box-sizing: border-box;
  margin-bottom: 16px;
  padding: 8px 12px;
  border: 1px solid var(--dt-line, #e6e8f0);
  border-radius: var(--dt-radius-sm, 6px);
  font-size: 14px;
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

.lead-block {
  margin: 8px 0;
  padding: 8px 12px;
  border-radius: var(--dt-radius-sm, 6px);
  background: var(--dt-danger-bg, #fff1f0);
  color: var(--dt-danger, #c0341d);
  font-size: 13px;
}

.historical-lost {
  margin: 8px 0;
  padding: 8px 12px;
  border-radius: var(--dt-radius-sm, 6px);
  background: var(--dt-warning-bg, #fffaf0);
  color: var(--dt-muted, #70778c);
  font-size: 13px;
}

.historical-lost-title {
  margin: 0 0 4px;
  font-weight: 600;
}

.historical-lost-item {
  display: flex;
  gap: 12px;
}

.lead-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 12px;
}
</style>
