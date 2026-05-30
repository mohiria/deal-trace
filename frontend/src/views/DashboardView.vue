<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Message } from '@arco-design/web-vue'
import type { TableColumnData } from '@arco-design/web-vue'
import { fetchDashboard } from '../api/dashboard'
import type { DashboardView } from '../api/dashboard'
import { formatLossRate, formatWonAmount } from '../utils/dashboard'
import { useAuthStore } from '../stores/auth'
import { useLeadsStore } from '../stores/leads'
import { ApiError } from '../api/client'
import type { LeadView } from '../api/leads'
import { suggestedClaims, staleOwnedLeads } from '../utils/workbench'
import LeadDetailPanel from '../components/LeadDetailPanel.vue'

/**
 * 工作台首屏（spec frontend-workbench）：枢纽式首屏 = 只读指标看板 + 内嵌线索工作区
 * （表格 + 详情抽屉，复用 LeadDetailPanel）+ 今日提醒/待办（客户端派生）。
 *
 * 指标看板区只读：首屏加载/刷新仅发只读查询（dashboard + leads + pool 均 GET），
 * 写操作仅由用户在抽屉或提醒中主动触发（spec MODIFIED 只读看板 / design D4）。
 * 口径由后端按登录角色裁决（不传视角参数）；指标值仅取后端返回，前端不重算。
 */
const router = useRouter()
const auth = useAuthStore()
const leads = useLeadsStore()

// ---- 指标看板（只读，三态）----
const loading = ref(false)
const error = ref(false)
const data = ref<DashboardView | null>(null)

async function load() {
  loading.value = true
  error.value = false
  try {
    data.value = await fetchDashboard()
  } catch {
    // UNAUTHORIZED 已由拦截器清退；此处仅标记非鉴权失败为可重试态。
    error.value = true
    data.value = null
  } finally {
    loading.value = false
  }
}

// ---- 线索工作区（表格 + 抽屉）----
const leadRows = computed<LeadView[]>(() => (auth.isAdmin ? leads.allLeads : leads.myLeads))

const columns: TableColumnData[] = [
  { title: '客户', slotName: 'customer' },
  { title: '业务类型', dataIndex: 'businessType' },
  { title: '年度', dataIndex: 'businessYear' },
  { title: '阶段', dataIndex: 'stage' },
  { title: '联系人', dataIndex: 'contactName' },
  { title: '最后跟踪', slotName: 'lastTracked' },
]

const activeLeadId = ref<number | null>(null)
function openLead(id: number) {
  activeLeadId.value = id
}
function closeDrawer() {
  activeLeadId.value = null
}

// ---- 今日提醒/待办（客户端派生，独立于指标口径）----
const suggested = computed(() => suggestedClaims(leads.pool))
const stale = computed(() => staleOwnedLeads(leads.myLeads))
const claimingId = ref<number | null>(null)

/** 建议条目内联认领：复用公海认领流程（成功移入名下 / 抢先冲突提示并刷新公海）。 */
async function onClaim(id: number) {
  claimingId.value = id
  try {
    await leads.claim(id)
    Message.success('认领成功，已移入我的线索')
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

function goCreate() {
  void router.push({ name: 'customers' })
}

onMounted(() => {
  // 首屏并发只读装载：指标 + 线索列表 + 公海（全部 GET）。
  void load()
  if (auth.isAdmin) {
    void leads.loadAllLeads()
  } else {
    void leads.loadMyLeads()
  }
  void leads.loadPool()
})
</script>

<template>
  <div class="workbench">
    <section class="dashboard">
      <header class="dashboard-head">
        <div>
          <h2 class="dashboard-title">销售工作台</h2>
          <p class="dashboard-sub">按当前归属推进业务线索，处理今日待办与公海认领。</p>
        </div>
        <div class="dashboard-quick">
          <a-button class="quick-new-lead" type="primary" @click="goCreate">新建线索</a-button>
          <a-button class="quick-new-customer" @click="goCreate">新建客户</a-button>
        </div>
      </header>

      <div v-if="loading" data-test="dashboard-loading" class="dashboard-state">
        <a-spin :size="28" />
        <span class="dashboard-state-text">加载中…</span>
      </div>

      <div v-else-if="error" data-test="dashboard-error" class="dashboard-state">
        <span class="dashboard-state-text">看板加载失败</span>
        <a-button data-test="dashboard-retry" type="primary" @click="load">重试</a-button>
      </div>

      <section v-else-if="data" data-test="dashboard-metrics" class="metric-grid" aria-label="Dashboard 指标">
        <article class="metric">
          <div class="metric-head">今日新增线索</div>
          <div class="metric-value" data-test="metric-today-new">{{ data.todayNewLeadCount }}</div>
        </article>
        <article class="metric">
          <div class="metric-head">公海待认领</div>
          <div class="metric-value" data-test="metric-open-sea">{{ data.openSeaUnclaimedCount }}</div>
        </article>
        <article class="metric">
          <div class="metric-head">本月赢单金额</div>
          <div class="metric-value" data-test="metric-won-amount">{{ formatWonAmount(data.monthlyWonAmount) }}</div>
        </article>
        <article class="metric">
          <div class="metric-head">本月流失率</div>
          <div class="metric-value" data-test="metric-loss-rate">{{ formatLossRate(data.monthlyLossRate) }}</div>
        </article>
      </section>
    </section>

    <div class="workbench-body">
      <!-- 线索工作区表格 -->
      <section data-test="workbench-leads" class="panel leads-workspace">
        <header class="panel-head">
          <h3 class="panel-title">{{ auth.isAdmin ? '全部线索' : '我的线索' }}</h3>
        </header>
        <a-table
          :data="leadRows"
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
            <div class="panel-empty">暂无线索</div>
          </template>
        </a-table>
      </section>

      <!-- 今日提醒 / 待办 -->
      <aside data-test="workbench-reminders" class="panel reminders">
        <header class="panel-head">
          <h3 class="panel-title">今日提醒</h3>
        </header>

        <div class="reminder-group">
          <div class="reminder-group-title">建议认领</div>
          <ul v-if="suggested.length" class="reminder-list">
            <li v-for="item in suggested" :key="`claim-${item.id}`" class="reminder-item">
              <span class="reminder-name">{{ item.customerName ?? '—' }}</span>
              <span class="reminder-meta">{{ item.businessType }} · {{ item.businessYear }}</span>
              <a-button
                v-if="!auth.isAdmin"
                class="reminder-claim"
                size="mini"
                type="primary"
                :loading="claimingId === item.id"
                @click="onClaim(item.id)"
              >
                认领
              </a-button>
            </li>
          </ul>
          <p v-else class="reminder-empty">公海暂无待认领线索</p>
        </div>

        <div class="reminder-group">
          <div class="reminder-group-title">长期未跟踪</div>
          <ul v-if="stale.length" class="reminder-list">
            <li v-for="item in stale" :key="`stale-${item.id}`" class="reminder-item">
              <a-link class="reminder-name reminder-stale-link" @click="openLead(item.id)">
                {{ item.customerName ?? '—' }}
              </a-link>
              <span class="reminder-meta">{{ item.lastTrackedAt ?? '尚未跟踪' }}</span>
            </li>
          </ul>
          <p v-else class="reminder-empty">名下线索跟踪及时</p>
        </div>
      </aside>
    </div>

    <!-- 线索详情抽屉：复用 LeadDetailPanel（详情页同源，design D1）。teleport 关闭以可测。 -->
    <a-drawer
      :visible="activeLeadId != null"
      :render-to-body="false"
      :width="560"
      :footer="false"
      title="线索详情"
      unmount-on-close
      @cancel="closeDrawer"
      @close="closeDrawer"
    >
      <div data-test="lead-drawer" class="drawer-body">
        <LeadDetailPanel v-if="activeLeadId != null" :key="activeLeadId" :lead-id="activeLeadId" />
      </div>
    </a-drawer>
  </div>
</template>

<style scoped>
.workbench {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.dashboard {
  background: var(--dt-surface, #ffffff);
  border: 1px solid var(--dt-line, #e6e8f0);
  border-radius: var(--dt-radius, 12px);
  box-shadow: var(--dt-shadow, 0 16px 40px rgba(36, 42, 66, 0.08));
  padding: 24px;
}

.dashboard-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 20px;
}

.dashboard-title {
  margin: 0 0 4px;
  font-size: 20px;
  font-weight: 700;
  color: var(--dt-text, #202438);
}

.dashboard-sub {
  margin: 0;
  font-size: 13px;
  color: var(--dt-muted, #70778c);
}

.dashboard-quick {
  display: flex;
  gap: 8px;
  flex-shrink: 0;
}

.dashboard-state {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 48px 24px;
  justify-content: center;
}

.dashboard-state-text {
  color: var(--dt-muted, #70778c);
}

.metric-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
}

@media (max-width: 900px) {
  .metric-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}

.metric {
  background: var(--dt-surface, #ffffff);
  border: 1px solid var(--dt-line, #e6e8f0);
  border-radius: var(--dt-radius, 12px);
  padding: 20px;
}

.metric-head {
  font-size: 13px;
  color: var(--dt-muted, #70778c);
  margin-bottom: 12px;
}

.metric-value {
  font-size: 28px;
  font-weight: 700;
  color: var(--dt-text, #202438);
}

.workbench-body {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 320px;
  gap: 16px;
}

@media (max-width: 1100px) {
  .workbench-body {
    grid-template-columns: 1fr;
  }
}

.panel {
  background: var(--dt-surface, #ffffff);
  border: 1px solid var(--dt-line, #e6e8f0);
  border-radius: var(--dt-radius, 12px);
  box-shadow: var(--dt-shadow, 0 16px 40px rgba(36, 42, 66, 0.08));
  padding: 20px;
}

.panel-head {
  margin-bottom: 12px;
}

.panel-title {
  margin: 0;
  font-size: 16px;
  font-weight: 700;
  color: var(--dt-text, #202438);
}

.lead-link {
  font-weight: 600;
}

.panel-empty {
  padding: 24px;
  text-align: center;
  color: var(--dt-muted, #70778c);
}

.reminder-group + .reminder-group {
  margin-top: 16px;
}

.reminder-group-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--dt-muted, #70778c);
  margin-bottom: 8px;
}

.reminder-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.reminder-item {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.reminder-name {
  font-weight: 600;
  color: var(--dt-text, #202438);
}

.reminder-meta {
  font-size: 12px;
  color: var(--dt-muted, #70778c);
}

.reminder-claim {
  margin-left: auto;
}

.reminder-empty {
  margin: 0;
  font-size: 13px;
  color: var(--dt-muted, #70778c);
}

.drawer-body {
  padding-right: 4px;
}
</style>
