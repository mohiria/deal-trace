<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { Message } from '@arco-design/web-vue'
import type { TableColumnData } from '@arco-design/web-vue'
import { ApiError } from '../api/client'
import { fetchContracts } from '../api/contracts'
import type { ContractRowView, ContractQuery } from '../api/contracts'
import { useAuthStore } from '../stores/auth'
import { useAccountsStore } from '../stores/accounts'
import { formatDateTime } from '../utils/datetime'

/**
 * 合同记录浏览（contract-view spec R1/R2）。ADMIN 全量、SALES 仅本人成交（后端依角色裁决）。
 * 服务端分页倒序；可选筛选：成交销售（仅 ADMIN）/ 签订日期闭区间 / 客户名·业务类型关键词。
 * 成交销售当前姓名与"公海赢单"由后端组装；金额前端千分位渲染（值为后端精确字符串）。
 */
const auth = useAuthStore()
const accounts = useAccountsStore()
const isAdmin = computed(() => auth.isAdmin)

const columns: TableColumnData[] = [
  { title: '客户', dataIndex: 'customerName', width: 220 },
  { title: '业务类型', dataIndex: 'businessType', width: 120 },
  { title: '合同金额', slotName: 'amount', width: 160 },
  { title: '签订日期', dataIndex: 'signedDate', width: 130 },
  { title: '成交销售', dataIndex: 'dealSalesName', width: 140 },
  { title: '赢单时间', width: 180, render: ({ record }) => formatDateTime((record as ContractRowView).createdAt) },
]

const rows = ref<ContractRowView[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const keyword = ref('')
const dealSalesId = ref<number | undefined>(undefined)
const signedDateFrom = ref<string | undefined>(undefined)
const signedDateTo = ref<string | undefined>(undefined)
const loading = ref(false)

/**
 * 成交销售筛选候选（仅 ADMIN 可见）：角色为 SALES 的账号（含已停用，便于回溯历史成交）。
 * "全部"由 a-select 的 allow-clear + placeholder 表达（清空即不带 dealSalesId）。
 */
const dealSalesOptions = computed(() =>
  accounts.accounts
    .filter((a) => a.role === 'SALES')
    .map((a) => ({ value: a.id, label: a.name })),
)

/** 千分位（金额为后端精确字符串，避免浮点）。 */
function formatThousands(amount: string): string {
  if (!amount) return amount
  const parts = amount.split('.')
  const grouped = (parts[0] ?? '').replace(/\B(?=(\d{3})+(?!\d))/g, ',')
  return parts[1] ? `${grouped}.${parts[1]}` : grouped
}

/** 成交销售选择 / 清空（a-select 值为选项 value 或清空时 undefined）。 */
function onDealSalesChange(value: unknown) {
  dealSalesId.value = typeof value === 'number' ? value : undefined
}

/** 签订日期区间选择：取 a-range-picker 的格式化字符串数组（[from, to]），清空则两端置空。 */
function onDateRangeChange(
  _value: unknown,
  _date: unknown,
  dateString?: (string | undefined)[],
) {
  signedDateFrom.value = dateString?.[0] || undefined
  signedDateTo.value = dateString?.[1] || undefined
}

async function load() {
  loading.value = true
  try {
    const query: ContractQuery = { page: page.value, size: size.value }
    if (keyword.value) query.keyword = keyword.value
    if (isAdmin.value && dealSalesId.value != null) query.dealSalesId = dealSalesId.value
    if (signedDateFrom.value) query.signedDateFrom = signedDateFrom.value
    if (signedDateTo.value) query.signedDateTo = signedDateTo.value
    const res = await fetchContracts(query)
    rows.value = res.items
    total.value = res.total
  } catch (error) {
    if (error instanceof ApiError) {
      Message.error(error.message)
    } else {
      Message.error('加载失败，请稍后重试')
    }
  } finally {
    loading.value = false
  }
}

const hasPagination = computed(() => total.value > size.value)

// 过滤变化回到第一页并重载；翻页直接重载。
watch([keyword, dealSalesId, signedDateFrom, signedDateTo], () => {
  page.value = 1
  void load()
})
watch(page, () => void load())

onMounted(() => {
  // 成交销售筛选仅 ADMIN 需要账号列表；失败不阻塞合同记录加载。
  if (isAdmin.value) {
    void accounts.loadAccounts().catch(() => {})
  }
  void load()
})

// 测试可观测/可驱动的筛选与分页状态（组件测试 seam）。
defineExpose({ keyword, dealSalesId, signedDateFrom, signedDateTo, page })
</script>

<template>
  <section class="contracts-page">
    <header class="contracts-head">
      <div>
        <h2 class="contracts-title">合同记录</h2>
        <p class="contracts-sub">赢单沉淀的合同记录，按赢单时间倒序。仅可查看，不可编辑或删除。</p>
      </div>
    </header>

    <div class="contracts-toolbar">
      <a-input-search
        v-model="keyword"
        class="contracts-filter-keyword"
        placeholder="按客户名称或业务类型搜索"
        allow-clear
        style="width: 260px"
      />
      <a-range-picker
        class="contracts-filter-date"
        style="width: 260px"
        @change="onDateRangeChange"
      />
      <a-select
        v-if="isAdmin"
        :model-value="dealSalesId as number"
        class="contracts-filter-dealsales"
        :options="dealSalesOptions"
        placeholder="全部成交销售"
        allow-clear
        style="width: 200px"
        @change="onDealSalesChange"
      />
    </div>

    <a-table
      :data="rows"
      :columns="columns"
      :pagination="false"
      :loading="loading"
      row-key="leadId"
      class="contracts-table"
      data-test="contracts-table"
    >
      <template #amount="{ record }">
        <span class="contracts-amount">¥{{ formatThousands(record.contractAmount) }}</span>
      </template>
      <template #empty>
        <div class="contracts-empty">暂无合同记录</div>
      </template>
    </a-table>

    <div v-if="hasPagination" class="pagination-bar" data-test="list-pagination">
      <a-pagination v-model:current="page" :total="total" :page-size="size" show-total />
    </div>
  </section>
</template>

<style scoped>
.contracts-page {
  background: var(--dt-surface, #ffffff);
  border: 1px solid var(--dt-line, #e6e8f0);
  border-radius: var(--dt-radius, 12px);
  box-shadow: var(--dt-shadow, 0 16px 40px rgba(36, 42, 66, 0.08));
  padding: 24px;
}

.contracts-head {
  margin-bottom: 16px;
}

.contracts-title {
  margin: 0;
  font-size: 20px;
  font-weight: 850;
  color: var(--dt-text, #202438);
}

.contracts-sub {
  margin: 6px 0 0;
  color: var(--dt-muted, #70778c);
  font-size: 13px;
}

.contracts-toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  padding: 14px;
  border: 1px solid var(--dt-line, #e6e8f0);
  border-bottom: 0;
  border-radius: var(--dt-radius, 12px) var(--dt-radius, 12px) 0 0;
  background: #fbfcff;
}

.contracts-table :deep(.arco-table-th) {
  background: #fbfcff;
  color: var(--dt-muted, #70778c);
  font-size: 12px;
  font-weight: 850;
}

.contracts-table :deep(.arco-table-td) {
  font-size: 13px;
}

.contracts-amount {
  font-weight: 800;
  color: var(--dt-text, #202438);
}

.contracts-empty {
  padding: 24px;
  text-align: center;
  color: var(--dt-muted, #70778c);
}

.pagination-bar {
  display: flex;
  justify-content: flex-end;
  padding: 14px 0 0;
}
</style>
