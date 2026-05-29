<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import type { TableColumnData } from '@arco-design/web-vue'
import { useAuthStore } from '../stores/auth'
import { useLeadsStore } from '../stores/leads'
import type { LeadView } from '../api/leads'

/**
 * 我的线索（spec R1）：Sales 看名下、Admin 看全部；可进入详情。
 */
const router = useRouter()
const auth = useAuthStore()
const leads = useLeadsStore()

const rows = computed<LeadView[]>(() => (auth.isAdmin ? leads.allLeads : leads.myLeads))

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
      <h2 class="leads-title">{{ auth.isAdmin ? '全部线索' : '我的线索' }}</h2>
    </header>

    <a-table
      :data="rows"
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
  margin-bottom: 16px;
}

.leads-title {
  margin: 0;
  font-size: 20px;
  font-weight: 700;
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
</style>
