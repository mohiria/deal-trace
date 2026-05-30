<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import type { TableColumnData } from '@arco-design/web-vue'
import { useAuthStore } from '../stores/auth'
import { useLeadsStore } from '../stores/leads'
import type { LeadView } from '../api/leads'
import CreateLeadModal from '../components/CreateLeadModal.vue'

/**
 * 我的线索（spec R1）：Sales 看名下、Admin 看全部；可进入详情。
 */
const router = useRouter()
const auth = useAuthStore()
const leads = useLeadsStore()
const keyword = ref('')
const currentPage = ref(1)
const pageSize = 10
const createLeadVisible = ref(false)

const rows = computed<LeadView[]>(() => (auth.isAdmin ? leads.allLeads : leads.myLeads))
const filteredRows = computed(() => {
  const search = keyword.value.trim().toLowerCase()
  if (!search) return rows.value
  return rows.value.filter((lead) =>
    [lead.customerName, lead.customerUsci, lead.businessType, lead.stage, lead.contactName, lead.contactPhone]
      .filter(Boolean)
      .join(' ')
      .toLowerCase()
      .includes(search),
  )
})
const pagedRows = computed(() => {
  const start = (currentPage.value - 1) * pageSize
  return filteredRows.value.slice(start, start + pageSize)
})

const columns: TableColumnData[] = [
  { title: '客户', slotName: 'customer' },
  { title: '业务类型', dataIndex: 'businessType' },
  { title: '年度', dataIndex: 'businessYear' },
  { title: '阶段', dataIndex: 'stage' },
  { title: '联系人', dataIndex: 'contactName' },
  { title: '最后跟踪', slotName: 'lastTracked' },
]

function openLead(id: number) {
  void router.push({ name: 'lead-detail', params: { id } })
}

function openCustomerCreate() {
  void router.push({ name: 'customers' })
}

async function refreshAfterCreate() {
  if (auth.isAdmin) {
    await leads.loadAllLeads()
  } else {
    await leads.loadMyLeads()
  }
}

watch(keyword, () => {
  currentPage.value = 1
})

onMounted(() => {
  if (auth.isAdmin) {
    void leads.loadAllLeads()
  } else {
    void leads.loadMyLeads()
  }
})
</script>

<template>
  <section class="leads-page">
    <header class="leads-head">
      <div>
        <h2 class="leads-title">{{ auth.isAdmin ? '全部线索' : '我的线索' }}</h2>
        <p class="leads-sub">按客户、业务类型、阶段和联系人快速定位线索。</p>
      </div>
      <div class="leads-actions">
        <a-button class="create-customer-open" @click="openCustomerCreate">新增客户</a-button>
        <a-button class="create-lead-open" type="primary" @click="createLeadVisible = true">新增线索</a-button>
      </div>
    </header>

    <div class="leads-toolbar">
      <input
        v-model="keyword"
        class="list-search"
        type="search"
        placeholder="搜索客户 / 信用代码 / 业务类型 / 联系人"
      />
    </div>

    <a-table
      :data="pagedRows"
      :columns="columns"
      :pagination="false"
      :loading="leads.loading"
      row-key="id"
      class="leads-table"
    >
      <template #customer="{ record }">
        <a-link class="lead-link" @click="openLead((record as LeadView).id)">
          {{ (record as LeadView).customerName ?? '—' }}
        </a-link>
      </template>
      <template #lastTracked="{ record }">
        {{ (record as LeadView).lastTrackedAt ?? '尚未跟踪' }}
      </template>
      <template #empty>
        <div class="leads-empty">暂无线索</div>
      </template>
    </a-table>
    <div v-if="filteredRows.length > pageSize" class="pagination-bar" data-test="list-pagination">
      <a-pagination v-model:current="currentPage" :total="filteredRows.length" :page-size="pageSize" show-total />
    </div>
    <CreateLeadModal v-model:visible="createLeadVisible" @created="refreshAfterCreate" />
  </section>
</template>

<style scoped>
.leads-page {
  background: var(--dt-surface, #ffffff);
  border: 1px solid var(--dt-line, #e6e8f0);
  border-radius: var(--dt-radius, 12px);
  box-shadow: var(--dt-shadow, 0 16px 40px rgba(36, 42, 66, 0.08));
  padding: 24px;
}

.leads-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 14px;
}

.leads-title {
  margin: 0;
  font-size: 20px;
  font-weight: 700;
  color: var(--dt-text, #202438);
}

.leads-sub {
  margin: 6px 0 0;
  color: var(--dt-muted, #70778c);
  font-size: 13px;
}

.leads-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  justify-content: flex-end;
}

.leads-toolbar {
  display: flex;
  align-items: center;
  padding: 14px;
  border: 1px solid var(--dt-line, #e6e8f0);
  border-bottom: 0;
  border-radius: var(--dt-radius, 12px) var(--dt-radius, 12px) 0 0;
  background: #fbfcff;
}

.list-search {
  width: min(440px, 100%);
  box-sizing: border-box;
  height: 34px;
  padding: 0 12px;
  border: 1px solid var(--dt-line, #e6e8f0);
  border-radius: var(--dt-radius-sm, 8px);
  font-size: 13px;
  color: var(--dt-text, #202438);
}

.lead-link {
  font-weight: 600;
}

.leads-empty {
  padding: 24px;
  text-align: center;
  color: var(--dt-muted, #70778c);
}

.leads-table :deep(.arco-table-th) {
  background: #fbfcff;
  color: var(--dt-muted, #70778c);
  font-size: 12px;
  font-weight: 850;
}

.leads-table :deep(.arco-table-td) {
  font-size: 13px;
}

.pagination-bar {
  display: flex;
  justify-content: flex-end;
  padding: 14px 0 0;
}
</style>
