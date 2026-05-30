<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { Message } from '@arco-design/web-vue'
import type { TableColumnData } from '@arco-design/web-vue'
import { useAuthStore } from '../stores/auth'
import { useLeadsStore } from '../stores/leads'
import { ApiError } from '../api/client'
import type { PoolLeadView } from '../api/leads'
import CreateLeadModal from '../components/CreateLeadModal.vue'

/**
 * 公海线索（spec R2）：列表展示后端返回的电话（Sales 脱敏 / Admin 明文）；
 * Sales 可认领，认领成功后线索移出公海；并发冲突（LEAD_ALREADY_CLAIMED）提示并刷新。
 */
const auth = useAuthStore()
const leads = useLeadsStore()
const router = useRouter()
const claimingId = ref<number | null>(null)
const keyword = ref('')
const currentPage = ref(1)
const pageSize = 10
const createLeadVisible = ref(false)

const columns: TableColumnData[] = [
  { title: '客户', dataIndex: 'customerName' },
  { title: '业务类型', dataIndex: 'businessType' },
  { title: '年度', dataIndex: 'businessYear' },
  { title: '联系人', dataIndex: 'contactName' },
  { title: '联系电话', dataIndex: 'contactPhone' },
  { title: '阶段', dataIndex: 'stage' },
  ...(auth.isAdmin ? [] : [{ title: '操作', slotName: 'operations' } as TableColumnData]),
]

const filteredRows = computed(() => {
  const search = keyword.value.trim().toLowerCase()
  if (!search) return leads.pool
  return leads.pool.filter((lead) =>
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

function openCustomerCreate() {
  void router.push({ name: 'customers' })
}

async function onClaim(id: number) {
  claimingId.value = id
  try {
    await leads.claim(id)
    Message.success('认领成功，已移入我的线索')
  } catch (error) {
    if (error instanceof ApiError && error.code === 'LEAD_ALREADY_CLAIMED') {
      Message.warning('该线索已被认领')
      await leads.loadPool()
    } else if (error instanceof ApiError) {
      Message.error(error.message)
    } else {
      Message.error('认领失败，请稍后重试')
    }
  } finally {
    claimingId.value = null
  }
}

watch(keyword, () => {
  currentPage.value = 1
})

onMounted(() => {
  void leads.loadPool()
})
</script>

<template>
  <section class="pool-page">
    <header class="pool-head">
      <div>
        <h2 class="pool-title">公海线索</h2>
        <p v-if="!auth.isAdmin" class="pool-hint">联系电话脱敏展示，认领后可见完整号码。</p>
      </div>
      <div class="pool-actions">
        <a-button class="create-customer-open" @click="openCustomerCreate">新增客户</a-button>
        <a-button class="create-lead-open" type="primary" @click="createLeadVisible = true">新增线索</a-button>
      </div>
    </header>

    <div class="pool-toolbar">
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
    >
      <template #operations="{ record }">
        <a-button
          type="primary"
          size="small"
          class="claim-btn"
          :loading="claimingId === (record as PoolLeadView).id"
          @click="onClaim((record as PoolLeadView).id)"
        >
          认领
        </a-button>
      </template>
      <template #empty>
        <div class="pool-empty">公海暂无线索</div>
      </template>
    </a-table>
    <div v-if="filteredRows.length > pageSize" class="pagination-bar" data-test="list-pagination">
      <a-pagination v-model:current="currentPage" :total="filteredRows.length" :page-size="pageSize" show-total />
    </div>
    <CreateLeadModal v-model:visible="createLeadVisible" @created="leads.loadPool" />
  </section>
</template>

<style scoped>
.pool-page {
  background: var(--dt-surface, #ffffff);
  border: 1px solid var(--dt-line, #e6e8f0);
  border-radius: var(--dt-radius, 12px);
  box-shadow: var(--dt-shadow, 0 16px 40px rgba(36, 42, 66, 0.08));
  padding: 24px;
}

.pool-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 14px;
}

.pool-title {
  margin: 0 0 4px;
  font-size: 20px;
  font-weight: 700;
  color: var(--dt-text, #202438);
}

.pool-hint {
  margin: 0;
  font-size: 13px;
  color: var(--dt-muted, #70778c);
}

.pool-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  justify-content: flex-end;
}

.pool-toolbar {
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

.pool-empty {
  padding: 24px;
  text-align: center;
  color: var(--dt-muted, #70778c);
}

.pool-page :deep(.arco-table-th) {
  background: #fbfcff;
  color: var(--dt-muted, #70778c);
  font-size: 12px;
  font-weight: 850;
}

.pool-page :deep(.arco-table-td) {
  font-size: 13px;
}

.pagination-bar {
  display: flex;
  justify-content: flex-end;
  padding: 14px 0 0;
}
</style>
