<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { Message } from '@arco-design/web-vue'
import type { TableColumnData } from '@arco-design/web-vue'
import { ApiError } from '../api/client'
import { fetchSystemLogs } from '../api/systemLogs'
import type { SystemLogView, SystemLogQuery } from '../api/systemLogs'

/**
 * 系统日志（全局浏览，spec R2）。仅 Admin 可达（路由 requiresAdmin + 后端 /admin/** 兜底）。
 * 服务端分页倒序；可选 action / target_type 过滤；LEAD 目标可跳详情。展示按当前姓名 + 金额千分位（后端组装）。
 */
const ACTION_OPTIONS: { value: string; label: string }[] = [
  { value: '', label: '全部动作' },
  { value: 'ACCOUNT_CREATE', label: '创建账号' },
  { value: 'ACCOUNT_ENABLE', label: '启用账号' },
  { value: 'ACCOUNT_DISABLE', label: '停用账号' },
  { value: 'LEAD_CREATE', label: '创建线索' },
  { value: 'LEAD_CLAIM', label: '认领' },
  { value: 'LEAD_RELEASE', label: '退回公海' },
  { value: 'LEAD_ASSIGN', label: '分配' },
  { value: 'LEAD_RECALL', label: '回收' },
  { value: 'LEAD_TRANSFER', label: '转移' },
  { value: 'LEAD_STAGE_CHANGE', label: '阶段变更' },
  { value: 'LEAD_WIN', label: '标记赢单' },
  { value: 'LEAD_LOSE', label: '标记流失' },
]
const TARGET_OPTIONS = [
  { value: '', label: '全部目标' },
  { value: 'LEAD', label: '线索' },
  { value: 'ACCOUNT', label: '账号' },
]

const columns: TableColumnData[] = [
  { title: '时间', dataIndex: 'createdAt', width: 180 },
  { title: '操作人', dataIndex: 'operatorName', width: 120 },
  { title: '动作', slotName: 'action', width: 110 },
  { title: '目标', slotName: 'target', width: 140 },
  { title: '摘要', slotName: 'summary' },
]

const rows = ref<SystemLogView[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const action = ref('') // '' = 全部
const targetType = ref('') // '' = 全部
const loading = ref(false)

function summaryText(s: SystemLogView): string {
  const d = s.detail
  if (!d) {
    return s.summaryFallback ?? '—'
  }
  const v = (k: string) => (d[k] == null ? '' : String(d[k]))
  const parts: string[] = []
  if ('fromOwnerName' in d || 'toOwnerName' in d) {
    parts.push(`${v('fromOwnerName') || '—'} → ${v('toOwnerName') || '—'}`)
  }
  if ('ownerName' in d) parts.push(`归属 ${v('ownerName') || '公海'}`)
  if ('fromStage' in d || 'toStage' in d) parts.push(`${v('fromStage') || '—'} → ${v('toStage') || '—'}`)
  if ('contractAmount' in d) {
    parts.push(`金额 ¥${formatThousands(v('contractAmount'))}`)
    if (v('signedDate')) parts.push(`签订 ${v('signedDate')}`)
  }
  if ('loseReason' in d) {
    parts.push(`流失 ${v('loseReason')}`)
    if (v('loseNote')) parts.push(v('loseNote'))
  }
  if ('accountName' in d) parts.push(v('accountName'))
  return parts.length ? parts.join(' · ') : (s.summaryFallback ?? '—')
}

/** 千分位（金额为后端精确字符串，避免浮点）。 */
function formatThousands(amount: string): string {
  if (!amount) return amount
  const parts = amount.split('.')
  const grouped = (parts[0] ?? '').replace(/\B(?=(\d{3})+(?!\d))/g, ',')
  return parts[1] ? `${grouped}.${parts[1]}` : grouped
}

async function load() {
  loading.value = true
  try {
    const query: SystemLogQuery = { page: page.value, size: size.value }
    if (action.value) query.action = action.value
    if (targetType.value) query.targetType = targetType.value
    const res = await fetchSystemLogs(query)
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
watch([action, targetType], () => {
  page.value = 1
  void load()
})
watch(page, () => void load())

onMounted(() => void load())

// 测试可观测/可驱动的筛选与分页状态（组件测试 seam）。
defineExpose({ action, targetType, page })
</script>

<template>
  <section class="syslog-page">
    <header class="syslog-head">
      <div>
        <h2 class="syslog-title">系统日志</h2>
        <p class="syslog-sub">全局审计事件，按时间倒序。仅可查看，不可编辑或删除。</p>
      </div>
    </header>

    <div class="syslog-toolbar">
      <a-select
        v-model="action"
        class="syslog-filter-action"
        :options="ACTION_OPTIONS"
        style="width: 200px"
      />
      <a-select
        v-model="targetType"
        class="syslog-filter-target"
        :options="TARGET_OPTIONS"
        style="width: 180px"
      />
    </div>

    <a-table
      :data="rows"
      :columns="columns"
      :pagination="false"
      :loading="loading"
      row-key="id"
      class="syslog-table"
      data-test="systemlog-table"
    >
      <template #action="{ record }">
        <span class="tag blue">{{ record.actionLabel }}</span>
      </template>
      <template #target="{ record }">
        <router-link
          v-if="record.targetType === 'LEAD' && record.leadId != null"
          class="syslog-target-link"
          :to="{ name: 'lead-detail', params: { id: record.leadId } }"
        >
          线索 #{{ record.targetId }}
        </router-link>
        <span v-else>{{ record.targetType === 'ACCOUNT' ? '账号' : record.targetType }} #{{ record.targetId }}</span>
      </template>
      <template #summary="{ record }">
        <span class="syslog-summary">{{ summaryText(record) }}</span>
      </template>
      <template #empty>
        <div class="syslog-empty">暂无系统日志</div>
      </template>
    </a-table>

    <div v-if="hasPagination" class="pagination-bar" data-test="list-pagination">
      <a-pagination v-model:current="page" :total="total" :page-size="size" show-total />
    </div>
  </section>
</template>

<style scoped>
.syslog-page {
  background: var(--dt-surface, #ffffff);
  border: 1px solid var(--dt-line, #e6e8f0);
  border-radius: var(--dt-radius, 12px);
  box-shadow: var(--dt-shadow, 0 16px 40px rgba(36, 42, 66, 0.08));
  padding: 24px;
}

.syslog-head {
  margin-bottom: 16px;
}

.syslog-title {
  margin: 0;
  font-size: 20px;
  font-weight: 850;
  color: var(--dt-text, #202438);
}

.syslog-sub {
  margin: 6px 0 0;
  color: var(--dt-muted, #70778c);
  font-size: 13px;
}

.syslog-toolbar {
  display: flex;
  gap: 12px;
  padding: 14px;
  border: 1px solid var(--dt-line, #e6e8f0);
  border-bottom: 0;
  border-radius: var(--dt-radius, 12px) var(--dt-radius, 12px) 0 0;
  background: #fbfcff;
}

.syslog-table :deep(.arco-table-th) {
  background: #fbfcff;
  color: var(--dt-muted, #70778c);
  font-size: 12px;
  font-weight: 850;
}

.syslog-table :deep(.arco-table-td) {
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

.syslog-target-link {
  color: #1d4ed8;
  font-weight: 700;
  text-decoration: none;
}

.syslog-summary {
  color: var(--dt-text, #202438);
}

.syslog-empty {
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
