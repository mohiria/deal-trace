<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { Message } from '@arco-design/web-vue'
import { fetchDashboard } from '../api/dashboard'
import type { DashboardView } from '../api/dashboard'
import { formatLossRate, formatWonAmount } from '../utils/dashboard'
import { useAuthStore } from '../stores/auth'
import { useLeadsStore } from '../stores/leads'
import { ApiError } from '../api/client'
import type { LeadView, PoolLeadView } from '../api/leads'
import { BUSINESS_TYPES } from '../utils/lead'
import LeadDetailPanel from '../components/LeadDetailPanel.vue'
import CreateLeadModal from '../components/CreateLeadModal.vue'

type LeadTab = 'mine' | 'pool' | 'all'
type WorkbenchRow = (LeadView | PoolLeadView) & { ownerLabel?: string }

const router = useRouter()
const auth = useAuthStore()
const leads = useLeadsStore()

const loading = ref(false)
const error = ref(false)
const data = ref<DashboardView | null>(null)

const activeTab = ref<LeadTab>('mine')
const search = ref('')
const typeFilter = ref('')
const stageFilter = ref('')
const activeLeadId = ref<number | null>(null)
const claimingId = ref<number | null>(null)
const createLeadVisible = ref(false)
const currentPage = ref(1)
const pageSize = 10

async function loadDashboard() {
  loading.value = true
  error.value = false
  try {
    data.value = await fetchDashboard()
  } catch {
    error.value = true
    data.value = null
  } finally {
    loading.value = false
  }
}

function goCreateCustomer() {
  void router.push({ name: 'customers' })
}

function openCreateLead() {
  createLeadVisible.value = true
}

function selectTab(tab: LeadTab) {
  activeTab.value = tab
  activeLeadId.value = null
  currentPage.value = 1
}

function openLead(id: number) {
  activeLeadId.value = id
}

function closeLeadDrawer() {
  activeLeadId.value = null
}

const baseOwnedRows = computed<LeadView[]>(() => (auth.isAdmin ? leads.allLeads : leads.myLeads))

const currentRows = computed<WorkbenchRow[]>(() => {
  if (activeTab.value === 'pool') {
    return leads.pool.map((lead) => ({ ...lead, ownerLabel: '公海' }))
  }
  if (activeTab.value === 'all') {
    return [
      ...baseOwnedRows.value.map((lead) => ({ ...lead, ownerLabel: ownerText(lead) })),
      ...leads.pool.map((lead) => ({ ...lead, ownerLabel: '公海' })),
    ]
  }
  return baseOwnedRows.value.map((lead) => ({ ...lead, ownerLabel: ownerText(lead) }))
})

const stageOptions = computed(() => {
  const stages = new Set<string>()
  currentRows.value.forEach((lead) => {
    if (lead.stage) {
      stages.add(lead.stage)
    }
  })
  return Array.from(stages)
})

const filteredRows = computed(() => {
  const keyword = search.value.trim().toLowerCase()
  return currentRows.value.filter((lead) => {
    const text = [lead.customerName, lead.customerUsci, lead.contactName, lead.contactPhone]
      .filter(Boolean)
      .join(' ')
      .toLowerCase()
    return (
      (!keyword || text.includes(keyword)) &&
      (!typeFilter.value || lead.businessType === typeFilter.value) &&
      (!stageFilter.value || lead.stage === stageFilter.value)
    )
  })
})

const tabCounts = computed(() => ({
  mine: baseOwnedRows.value.length,
  pool: leads.pool.length,
  all: baseOwnedRows.value.length + leads.pool.length,
}))

const pagedRows = computed(() => {
  const start = (currentPage.value - 1) * pageSize
  return filteredRows.value.slice(start, start + pageSize)
})

watch([search, typeFilter, stageFilter], () => {
  currentPage.value = 1
})

const metricCards = computed(() => {
  if (!data.value) {
    return []
  }
  return [
    {
      key: 'today',
      title: '今日新增线索',
      value: String(data.value.todayNewLeadCount),
      tag: '当前归属',
      note: auth.isAdmin ? '全局今日新建' : '名下今日新建',
      tone: 'blue',
      test: 'metric-today-new',
    },
    {
      key: 'pool',
      title: '公海待认领',
      value: String(data.value.openSeaUnclaimedCount),
      tag: '全局公海',
      note: '未结束且无归属',
      tone: 'orange',
      test: 'metric-open-sea',
    },
    {
      key: 'won',
      title: '本月赢单金额',
      value: formatWonAmount(data.value.monthlyWonAmount),
      tag: '事件归属',
      note: `${data.value.monthlyEndedEventCount} 条结束事件`,
      tone: 'green',
      test: 'metric-won-amount',
    },
    {
      key: 'loss',
      title: '本月流失率',
      value: formatLossRate(data.value.monthlyLossRate),
      tag: '需关注',
      note: `${data.value.monthlyLostEventCount} 条流失`,
      tone: 'red',
      test: 'metric-loss-rate',
    },
  ]
})

async function onClaim(id: number) {
  claimingId.value = id
  try {
    const claimed = await leads.claim(id)
    Message.success('认领成功，已移入我的线索')
    activeTab.value = 'mine'
    activeLeadId.value = claimed.id
    await leads.loadMyLeads()
  } catch (e) {
    if (e instanceof ApiError && e.code === 'LEAD_ALREADY_CLAIMED') {
      Message.warning('该线索已被认领')
      await leads.loadPool()
    } else if (e instanceof ApiError) {
      Message.error(e.message)
    } else {
      Message.error('认领失败，请稍后重试')
    }
  } finally {
    claimingId.value = null
  }
}

async function refreshLeadListsAfterCreate() {
  if (auth.isAdmin) {
    await leads.loadAllLeads()
  } else {
    await leads.loadMyLeads()
  }
  await leads.loadPool()
}

function ownerText(lead: Pick<LeadView, 'ownerSalesId'>): string {
  return lead.ownerSalesId == null ? '公海' : `销售 #${lead.ownerSalesId}`
}

function stageTone(stage: string | null): string {
  if (stage === '已赢单') return 'green'
  if (stage === '已流失') return 'red'
  if (stage === '商务谈判') return 'orange'
  if (stage === '方案报价') return 'blue'
  return 'purple'
}

function typeTone(type: string | null): string {
  if (type === 'BIM咨询') return 'blue'
  if (type === 'BIM培训') return 'green'
  return 'purple'
}

function formatDate(raw: string | null | undefined): string {
  if (!raw) return '暂无'
  return raw.replace('T', ' ').slice(0, 16)
}

onMounted(() => {
  void loadDashboard()
  if (auth.isAdmin) {
    void leads.loadAllLeads()
  } else {
    void leads.loadMyLeads()
  }
  void leads.loadPool()
})
</script>

<template>
  <div class="hub" :class="{ 'has-drawer': activeLeadId != null }">
    <main class="hub-main">
      <header class="topbar">
        <div class="title-block">
          <h1>销售工作台</h1>
          <p>按当前归属、业务类型和阶段快速推进业务线索。</p>
        </div>
        <div class="top-actions">
          <a-button class="quick-new-customer" @click="goCreateCustomer">新增客户</a-button>
          <a-button class="quick-new-lead" type="primary" @click="openCreateLead">新增线索</a-button>
        </div>
      </header>

      <div v-if="loading" data-test="dashboard-loading" class="dashboard-state">
        <a-spin :size="28" />
        <span>加载中...</span>
      </div>
      <div v-else-if="error" data-test="dashboard-error" class="dashboard-state">
        <span>看板加载失败</span>
        <a-button data-test="dashboard-retry" type="primary" @click="loadDashboard">重试</a-button>
      </div>
      <section v-else-if="data" data-test="dashboard-metrics" class="metric-grid" aria-label="Dashboard 指标">
        <article v-for="metric in metricCards" :key="metric.key" class="metric">
          <div class="metric-head">
            <span>{{ metric.title }}</span>
            <span class="metric-mark" :class="metric.tone" aria-hidden="true"></span>
          </div>
          <div class="metric-value" :data-test="metric.test">{{ metric.value }}</div>
          <div class="metric-foot">
            <span class="tag" :class="metric.tone">{{ metric.tag }}</span>
            {{ metric.note }}
          </div>
        </article>
      </section>

      <section data-test="workbench-leads" class="workbench-card">
        <header class="workbench-head">
          <div class="tabs" aria-label="线索视图">
            <button class="tab" :class="{ active: activeTab === 'mine' }" type="button" @click="selectTab('mine')">
              我的线索
              <span>{{ tabCounts.mine }}</span>
            </button>
            <button class="tab" :class="{ active: activeTab === 'pool' }" type="button" @click="selectTab('pool')">
              公海线索
              <span>{{ tabCounts.pool }}</span>
            </button>
            <button class="tab" :class="{ active: activeTab === 'all' }" type="button" @click="selectTab('all')">
              全部线索
              <span>{{ tabCounts.all }}</span>
            </button>
          </div>
          <div class="filters">
            <input v-model="search" class="control search" type="search" placeholder="搜索客户 / 信用代码 / 联系人" />
            <select v-model="typeFilter" class="control">
              <option value="">全部业务类型</option>
              <option v-for="type in BUSINESS_TYPES" :key="type" :value="type">{{ type }}</option>
            </select>
            <select v-model="stageFilter" class="control">
              <option value="">全部阶段</option>
              <option v-for="stage in stageOptions" :key="stage" :value="stage">{{ stage }}</option>
            </select>
          </div>
        </header>

        <div class="table-wrap">
          <table aria-label="业务线索列表">
            <thead>
              <tr>
                <th>客户 / 线索</th>
                <th>业务类型</th>
                <th>阶段</th>
                <th>联系人</th>
                <th>归属</th>
                <th>最后跟踪</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="lead in pagedRows" :key="lead.id" :class="{ selected: activeLeadId === lead.id }">
                <td>
                  <button class="lead-link" type="button" @click="openLead(lead.id)">
                    <strong>{{ lead.customerName ?? '—' }}</strong>
                    <span>{{ lead.customerUsci ?? '无信用代码' }}</span>
                  </button>
                </td>
                <td><span class="tag" :class="typeTone(lead.businessType)">{{ lead.businessType ?? '—' }}</span></td>
                <td><span class="tag" :class="stageTone(lead.stage)">{{ lead.stage ?? '—' }}</span></td>
                <td>{{ lead.contactName ?? '—' }}</td>
                <td><span class="tag" :class="lead.ownerLabel === '公海' ? 'orange' : 'blue'">{{ lead.ownerLabel }}</span></td>
                <td>{{ formatDate(lead.lastTrackedAt) }}</td>
                <td>
                  <div class="row-actions">
                    <button class="mini-btn primary" type="button" @click="openLead(lead.id)">详情</button>
                    <button
                      v-if="activeTab === 'pool' && !auth.isAdmin"
                      class="mini-btn claim-btn"
                      type="button"
                      :disabled="claimingId === lead.id"
                      @click="onClaim(lead.id)"
                    >
                      {{ claimingId === lead.id ? '认领中' : '认领' }}
                    </button>
                  </div>
                </td>
              </tr>
              <tr v-if="filteredRows.length === 0">
                <td colspan="7" class="empty-row">暂无匹配线索</td>
              </tr>
            </tbody>
          </table>
        </div>
        <div v-if="filteredRows.length > pageSize" class="pagination-bar" data-test="lead-pagination">
          <a-pagination v-model:current="currentPage" :total="filteredRows.length" :page-size="pageSize" show-total />
        </div>
      </section>
    </main>

    <aside v-if="activeLeadId != null" data-test="lead-drawer" class="detail-drawer" aria-label="业务线索详情">
      <button
        class="drawer-close"
        data-test="lead-drawer-close"
        type="button"
        aria-label="关闭线索详情"
        @click="closeLeadDrawer"
      >
        ×
      </button>
      <LeadDetailPanel :key="activeLeadId" :lead-id="activeLeadId" />
    </aside>
    <CreateLeadModal v-model:visible="createLeadVisible" @created="refreshLeadListsAfterCreate" />
  </div>
</template>

<style scoped>
.hub {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 18px;
  align-items: start;
}

.hub.has-drawer {
  grid-template-columns: minmax(0, 1fr) 440px;
}

.hub-main {
  min-width: 0;
}

.topbar {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  align-items: flex-start;
  margin-bottom: 18px;
}

.title-block h1 {
  margin: 0 0 6px;
  font-size: 28px;
  line-height: 1.15;
  font-weight: 850;
  color: var(--dt-text);
}

.title-block p {
  margin: 0;
  color: var(--dt-muted);
  font-size: 14px;
}

.top-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  justify-content: flex-end;
}

.dashboard-state {
  min-height: 120px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  margin-bottom: 18px;
  background: var(--dt-surface);
  border: 1px solid var(--dt-line);
  border-radius: var(--dt-radius);
}

.metric-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(150px, 1fr));
  gap: 14px;
  margin-bottom: 18px;
}

.metric {
  background: var(--dt-surface);
  border: 1px solid var(--dt-line);
  border-radius: var(--dt-radius);
  padding: 16px;
  min-height: 120px;
  box-shadow: 0 10px 28px rgba(31, 36, 56, 0.04);
}

.metric-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  color: var(--dt-muted);
  font-size: 13px;
  font-weight: 700;
  margin-bottom: 14px;
}

.metric-value {
  font-size: 28px;
  font-weight: 850;
  line-height: 1;
  margin-bottom: 10px;
  color: var(--dt-text);
}

.metric-foot {
  color: var(--dt-muted);
  font-size: 12px;
  display: flex;
  gap: 6px;
  align-items: center;
  flex-wrap: wrap;
}

.metric-mark {
  width: 10px;
  height: 10px;
  border-radius: 999px;
  box-shadow: 0 0 0 4px rgba(37, 99, 255, 0.08);
}

.metric-mark.blue {
  background: #2563ff;
}

.metric-mark.green {
  background: var(--dt-green);
  box-shadow: 0 0 0 4px rgba(15, 159, 110, 0.1);
}

.metric-mark.orange {
  background: var(--dt-orange);
  box-shadow: 0 0 0 4px rgba(217, 119, 6, 0.1);
}

.metric-mark.red {
  background: var(--dt-red);
  box-shadow: 0 0 0 4px rgba(220, 38, 38, 0.1);
}

.workbench-card {
  background: var(--dt-surface);
  border: 1px solid var(--dt-line);
  border-radius: var(--dt-radius);
  box-shadow: var(--dt-shadow);
  overflow: hidden;
}

.workbench-head {
  padding: 16px;
  border-bottom: 1px solid var(--dt-line);
  display: grid;
  grid-template-columns: 1fr auto;
  gap: 14px;
  align-items: center;
}

.tabs {
  display: inline-flex;
  background: var(--dt-surface-soft);
  border: 1px solid var(--dt-line);
  border-radius: 10px;
  padding: 4px;
  gap: 4px;
  width: fit-content;
}

.tab {
  min-height: 30px;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 0 12px;
  color: var(--dt-muted);
  border-radius: 7px;
  border: 0;
  background: transparent;
  font-weight: 800;
  font-size: 13px;
  white-space: nowrap;
  cursor: pointer;
}

.tab.active {
  color: #1e3a8a;
  background: #fff;
  box-shadow: 0 5px 12px rgba(34, 40, 62, 0.08);
}

.tab span {
  color: var(--dt-muted-2);
  font-weight: 800;
}

.filters {
  display: flex;
  gap: 8px;
  align-items: center;
  justify-content: flex-end;
  flex-wrap: wrap;
}

.control {
  height: 34px;
  border: 1px solid var(--dt-line-strong);
  border-radius: 8px;
  background: #fff;
  padding: 0 10px;
  color: var(--dt-text);
  font-size: 13px;
}

.search {
  min-width: 230px;
}

.table-wrap {
  overflow: auto;
}

table {
  width: 100%;
  border-collapse: separate;
  border-spacing: 0;
  font-size: 13px;
}

th,
td {
  padding: 13px 14px;
  border-bottom: 1px solid #eef1f6;
  text-align: left;
  vertical-align: middle;
  white-space: nowrap;
}

th {
  color: var(--dt-muted);
  background: #fbfcff;
  font-size: 12px;
  font-weight: 850;
}

tr.selected td {
  background: #fbfdff;
}

.lead-link {
  border: 0;
  background: transparent;
  padding: 0;
  color: var(--dt-text);
  text-align: left;
  cursor: pointer;
  display: grid;
  gap: 3px;
}

.lead-link strong {
  font-size: 13px;
}

.lead-link span {
  color: var(--dt-muted-2);
  font-size: 12px;
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
  background: var(--dt-brand-soft);
  color: #1d4ed8;
}

.tag.green {
  background: var(--dt-green-soft);
  color: var(--dt-green);
}

.tag.orange {
  background: var(--dt-orange-soft);
  color: var(--dt-orange);
}

.tag.purple {
  background: var(--dt-purple-soft);
  color: var(--dt-purple);
}

.tag.red {
  background: var(--dt-red-soft);
  color: var(--dt-red);
}

.row-actions {
  display: inline-flex;
  gap: 6px;
  white-space: nowrap;
}

.mini-btn {
  height: 28px;
  padding: 0 9px;
  border-radius: 7px;
  border: 1px solid var(--dt-line-strong);
  background: #fff;
  font-weight: 800;
  color: #4b5568;
  font-size: 12px;
  cursor: pointer;
}

.mini-btn.primary {
  background: var(--dt-brand-soft);
  border-color: #cfe0ff;
  color: #1749d5;
}

.mini-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.empty-row {
  text-align: center;
  color: var(--dt-muted);
  padding: 36px;
}

.pagination-bar {
  display: flex;
  justify-content: flex-end;
  padding: 14px 16px;
  border-top: 1px solid #eef1f6;
  background: #fff;
}

.detail-drawer {
  background: #fff;
  border-left: 1px solid var(--dt-line);
  border-radius: var(--dt-radius);
  padding: 24px;
  min-height: calc(100vh - 112px);
  max-height: calc(100vh - 48px);
  position: sticky;
  top: 24px;
  overflow: auto;
  box-shadow: -8px 16px 36px rgba(31, 36, 56, 0.06);
}

.drawer-close {
  position: absolute;
  top: 18px;
  right: 18px;
  width: 30px;
  height: 30px;
  border-radius: 8px;
  border: 1px solid var(--dt-line);
  background: #fff;
  color: var(--dt-muted);
  font-size: 18px;
  line-height: 1;
  cursor: pointer;
}

.drawer-close:hover {
  color: var(--dt-text);
  background: var(--dt-surface-soft);
}

@media (max-width: 1280px) {
  .hub,
  .hub.has-drawer {
    grid-template-columns: 1fr;
  }

  .detail-drawer {
    position: static;
    min-height: auto;
    max-height: none;
  }
}

@media (max-width: 980px) {
  .metric-grid {
    grid-template-columns: repeat(2, minmax(150px, 1fr));
  }

  .workbench-head,
  .topbar {
    grid-template-columns: 1fr;
    display: grid;
  }

  .filters {
    justify-content: flex-start;
  }
}
</style>
