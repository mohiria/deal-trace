<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { Message } from '@arco-design/web-vue'
import { useAuthStore } from '../stores/auth'
import { useLeadsStore } from '../stores/leads'
import { useAccountsStore } from '../stores/accounts'
import { ApiError } from '../api/client'
import {
  ACTIVE_STAGES,
  LOSE_REASONS,
  LOSE_REASON_OTHER,
  TRACK_METHODS,
  formatAmount,
  isClosed,
  isValidAmount,
} from '../utils/lead'

/**
 * 线索详情（spec R3–R7）：详情 + 倒序进度流 + 追加进度 + 阶段推进 + 赢单/流失 + 退回公海 + 闭单只读。
 * 写入口的显隐叠加"线索是否结束"（D4 集中派生）；越权/失效以后端裁决回落（D5）。
 */
const route = useRoute()
const auth = useAuthStore()
const leads = useLeadsStore()
const accounts = useAccountsStore()

const leadId = Number(route.params.id)

const lead = computed(() => leads.currentLead)
const closed = computed(() => isClosed(lead.value?.stage))
const isOwner = computed(
  () => lead.value != null && auth.currentUser != null && lead.value.ownerSalesId === auth.currentUser.id,
)
/** 进度追加 / 退回公海：仅 SALES 名下（后端同样限制）。 */
const canSalesOwn = computed(() => !auth.isAdmin && isOwner.value)
/** 阶段 / 赢单 / 流失：ADMIN 任意，或 SALES 名下。 */
const canStageActions = computed(() => auth.isAdmin || canSalesOwn.value)

const showProgressAdd = computed(() => !closed.value && canSalesOwn.value)
const showStageWinLose = computed(() => !closed.value && canStageActions.value)
const showRelease = computed(() => !closed.value && canSalesOwn.value)

/** Admin 归属操作（design D3）：仅 ADMIN + 未结束线索呈现；按 ownerSalesId 分叉。 */
const showOwnership = computed(() => auth.isAdmin && !closed.value)
const isPool = computed(() => lead.value?.ownerSalesId == null)
/** 分配候选 = 启用 Sales（D4）。 */
const assignCandidates = computed(() => accounts.enabledSales)
/** 转移候选 = 启用 Sales 排除当前归属。 */
const transferCandidates = computed(() =>
  accounts.enabledSales.filter((s) => s.id !== lead.value?.ownerSalesId),
)

const targetStages = computed(() => ACTIVE_STAGES.filter((s) => s !== lead.value?.stage))

// 追加进度表单
const progress = reactive({ method: TRACK_METHODS[0]!, content: '' })
// 弹窗可见性
const winVisible = ref(false)
const loseVisible = ref(false)
const releaseVisible = ref(false)
const assignVisible = ref(false)
const transferVisible = ref(false)
// 弹窗表单
const winForm = reactive({ amount: '', signedDate: '' })
const loseForm = reactive({ reason: '', note: '' })
const releaseForm = reactive({ note: '' })
// 目标销售 id；0 为「未选择」哨兵（账号 id 恒为正）。a-radio-group modelValue 不接受 null/undefined。
const assignTarget = ref(0)
const transferTarget = ref(0)
const acting = ref(false)

const winAmountPreview = computed(() => formatAmount(winForm.amount))
const loseNoteRequired = computed(() => loseForm.reason === LOSE_REASON_OTHER)

function isValidDate(raw: string): boolean {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(raw.trim())) {
    return false
  }
  return !Number.isNaN(Date.parse(raw.trim()))
}

/** 写动作统一回落：LEAD_ENDED_READONLY 提示并据后端刷新只读态；其余展示后端 message。 */
async function runWrite(fn: () => Promise<unknown>): Promise<boolean> {
  acting.value = true
  try {
    await fn()
    return true
  } catch (error) {
    if (error instanceof ApiError && error.code === 'LEAD_ENDED_READONLY') {
      Message.error('线索已结束，操作未生效')
      await leads.loadLead(leadId)
    } else if (error instanceof ApiError) {
      Message.error(error.message)
    } else {
      Message.error('操作失败，请稍后重试')
    }
    return false
  } finally {
    acting.value = false
  }
}

async function onAddProgress() {
  if (progress.content.trim() === '') {
    Message.warning('请填写跟踪内容')
    return
  }
  const ok = await runWrite(() => leads.addLeadProgress(leadId, progress.method, progress.content.trim()))
  if (ok) {
    progress.content = ''
    Message.success('已新增进度')
  }
}

async function onStageChange(stage: string) {
  await runWrite(() => leads.changeStage(leadId, stage))
}

async function onWinConfirm() {
  if (!isValidAmount(winForm.amount) || !isValidDate(winForm.signedDate)) {
    Message.warning('请填写正确的合同金额（>0，至多两位小数）与签订日期')
    return
  }
  const ok = await runWrite(() => leads.winLead(leadId, winForm.amount.trim(), winForm.signedDate.trim()))
  if (ok) {
    winVisible.value = false
  }
}

async function onLoseConfirm() {
  if (loseForm.reason === '') {
    Message.warning('请选择流失原因')
    return
  }
  if (loseNoteRequired.value && loseForm.note.trim() === '') {
    Message.warning('流失原因为「其他」时必须填写流失说明')
    return
  }
  const ok = await runWrite(() => leads.loseLead(leadId, loseForm.reason, loseForm.note.trim() || undefined))
  if (ok) {
    loseVisible.value = false
  }
}

async function onReleaseConfirm() {
  if (releaseForm.note.trim() === '') {
    Message.warning('请填写退回备注')
    return
  }
  const ok = await runWrite(() => leads.release(leadId, releaseForm.note.trim()))
  if (ok) {
    releaseVisible.value = false
    Message.success('已退回公海')
  }
}

function openAssign() {
  assignTarget.value = 0
  assignVisible.value = true
}

function openTransfer() {
  transferTarget.value = 0
  transferVisible.value = true
}

/** 归属写动作失败时据后端刷新该线索（状态机相关，避免前端态漂移，design D3/D5）。 */
async function onAssignConfirm() {
  if (!assignTarget.value) {
    Message.warning('请选择目标销售')
    return
  }
  const ok = await runWrite(() => leads.assign(leadId, assignTarget.value))
  assignVisible.value = false
  if (ok) {
    Message.success('已分配')
  } else {
    await leads.loadLead(leadId)
  }
}

async function onRecall() {
  const ok = await runWrite(() => leads.recall(leadId))
  if (ok) {
    Message.success('已回收至公海')
  } else {
    await leads.loadLead(leadId)
  }
}

async function onTransferConfirm() {
  if (!transferTarget.value) {
    Message.warning('请选择目标销售')
    return
  }
  const ok = await runWrite(() => leads.transfer(leadId, transferTarget.value))
  transferVisible.value = false
  if (ok) {
    Message.success('已转移')
  } else {
    await leads.loadLead(leadId)
  }
}

onMounted(() => {
  void leads.loadLead(leadId)
  void leads.loadProgress(leadId)
  // Admin 需要启用 Sales 列表作分配 / 转移候选（D4）；Sales 详情页不拉账号。
  if (auth.isAdmin) {
    void accounts.loadAccounts()
  }
})
</script>

<template>
  <section v-if="lead" class="detail-page">
    <header class="detail-head">
      <div>
        <h2 class="detail-title">{{ lead.customerName ?? '线索详情' }}</h2>
        <span class="detail-sub">{{ lead.businessType }} · {{ lead.businessYear }} 年度</span>
      </div>
      <a-tag :color="closed ? 'gray' : 'arcoblue'" class="detail-stage">{{ lead.stage }}</a-tag>
    </header>

    <a-descriptions :column="2" bordered class="detail-desc">
      <a-descriptions-item label="客户名称">{{ lead.customerName ?? '—' }}</a-descriptions-item>
      <a-descriptions-item label="统一社会信用代码">{{ lead.customerUsci ?? '—' }}</a-descriptions-item>
      <a-descriptions-item label="联系人">{{ lead.contactName ?? '—' }}</a-descriptions-item>
      <a-descriptions-item label="联系电话">{{ lead.contactPhone ?? '—' }}</a-descriptions-item>
      <a-descriptions-item label="线索来源">{{ lead.leadSource ?? '—' }}</a-descriptions-item>
      <a-descriptions-item label="当前归属">
        {{ lead.ownerSalesId == null ? '公海' : `销售 #${lead.ownerSalesId}` }}
      </a-descriptions-item>
      <a-descriptions-item label="最后跟踪">{{ lead.lastTrackedAt ?? '尚未跟踪' }}</a-descriptions-item>
      <a-descriptions-item label="创建时间">{{ lead.createdAt ?? '—' }}</a-descriptions-item>
    </a-descriptions>

    <a-alert v-if="lead.stage === '已流失'" type="warning" class="detail-lose">
      流失原因：{{ lead.loseReason ?? '—' }}<span v-if="lead.loseNote">；说明：{{ lead.loseNote }}</span>
    </a-alert>

    <!-- 写操作区：闭单只读时整体收起 -->
    <div v-if="showStageWinLose || showRelease" class="detail-actions">
      <template v-if="showStageWinLose">
        <a-button
          v-for="s in targetStages"
          :key="s"
          class="stage-btn"
          size="small"
          :loading="acting"
          @click="onStageChange(s)"
        >
          推进至{{ s }}
        </a-button>
        <a-button class="win-open" type="primary" size="small" @click="winVisible = true">标记赢单</a-button>
        <a-button class="lose-open" status="danger" size="small" @click="loseVisible = true">标记流失</a-button>
      </template>
      <a-button v-if="showRelease" class="release-open" size="small" @click="releaseVisible = true">
        退回公海
      </a-button>
    </div>

    <!-- Admin 归属操作区（D3）：未结束线索；公海→分配，有归属→回收/转移 -->
    <div v-if="showOwnership" class="ownership-actions">
      <span class="ownership-label">归属调度</span>
      <a-button v-if="isPool" class="assign-open" type="primary" size="small" @click="openAssign">
        分配给销售
      </a-button>
      <template v-else>
        <a-button class="recall-btn" size="small" :loading="acting" @click="onRecall">回收至公海</a-button>
        <a-button class="transfer-open" type="primary" size="small" @click="openTransfer">转移给销售</a-button>
      </template>
    </div>

    <!-- 进度跟踪流（倒序，由后端保证顺序） -->
    <section class="detail-progress">
      <h3 class="progress-title">进度跟踪</h3>

      <form v-if="showProgressAdd" class="progress-form" @submit.prevent="onAddProgress">
        <a-radio-group v-model="progress.method" class="progress-method">
          <a-radio v-for="m in TRACK_METHODS" :key="m" :value="m">{{ m }}</a-radio>
        </a-radio-group>
        <a-textarea
          v-model="progress.content"
          class="progress-content"
          placeholder="填写跟踪内容（必填）"
          :auto-size="{ minRows: 2 }"
        />
        <a-button class="progress-submit" type="primary" html-type="submit" :loading="acting">
          新增进度
        </a-button>
      </form>

      <a-timeline v-if="leads.progress.length" class="progress-list">
        <a-timeline-item v-for="p in leads.progress" :key="p.id">
          <div class="progress-item-head">
            <span class="progress-item-method">{{ p.method }}</span>
            <span class="progress-item-time">{{ p.trackTime }}</span>
            <span class="progress-item-tracker">{{ p.trackerName ?? '—' }}</span>
          </div>
          <div class="progress-item-content">{{ p.content }}</div>
        </a-timeline-item>
      </a-timeline>
      <p v-else class="progress-empty">暂无进度跟踪</p>
    </section>

    <!-- 赢单弹窗 -->
    <a-modal
      :visible="winVisible"
      :render-to-body="false"
      :footer="false"
      title="标记赢单"
      @cancel="winVisible = false"
    >
      <div class="win-modal">
        <a-input v-model="winForm.amount" class="win-amount" placeholder="合同金额（元，>0，至多两位小数）" />
        <p v-if="winAmountPreview" class="win-preview">合同金额：{{ winAmountPreview }} 元</p>
        <a-input v-model="winForm.signedDate" class="win-date" placeholder="签订日期 YYYY-MM-DD" />
        <div class="modal-footer">
          <a-button @click="winVisible = false">取消</a-button>
          <a-button class="win-confirm" type="primary" :loading="acting" @click="onWinConfirm">确定赢单</a-button>
        </div>
      </div>
    </a-modal>

    <!-- 流失弹窗 -->
    <a-modal
      :visible="loseVisible"
      :render-to-body="false"
      :footer="false"
      title="标记流失"
      @cancel="loseVisible = false"
    >
      <div class="lose-modal">
        <a-radio-group v-model="loseForm.reason" direction="vertical" class="lose-reason">
          <a-radio v-for="r in LOSE_REASONS" :key="r" :value="r">{{ r }}</a-radio>
        </a-radio-group>
        <a-textarea
          v-model="loseForm.note"
          class="lose-note"
          :placeholder="loseNoteRequired ? '流失说明（必填）' : '流失说明（选填）'"
          :auto-size="{ minRows: 2 }"
        />
        <div class="modal-footer">
          <a-button @click="loseVisible = false">取消</a-button>
          <a-button class="lose-confirm" status="danger" :loading="acting" @click="onLoseConfirm">确定流失</a-button>
        </div>
      </div>
    </a-modal>

    <!-- 退回公海弹窗 -->
    <a-modal
      :visible="releaseVisible"
      :render-to-body="false"
      :footer="false"
      title="退回公海"
      @cancel="releaseVisible = false"
    >
      <div class="release-modal">
        <a-textarea
          v-model="releaseForm.note"
          class="release-note"
          placeholder="退回备注（必填）"
          :auto-size="{ minRows: 2 }"
        />
        <div class="modal-footer">
          <a-button @click="releaseVisible = false">取消</a-button>
          <a-button class="release-confirm" type="primary" :loading="acting" @click="onReleaseConfirm">
            确定退回
          </a-button>
        </div>
      </div>
    </a-modal>

    <!-- 分配弹窗（公海线索 → 启用 Sales） -->
    <a-modal
      :visible="assignVisible"
      :render-to-body="false"
      :footer="false"
      title="分配线索"
      @cancel="assignVisible = false"
    >
      <div class="assign-modal">
        <a-radio-group v-model="assignTarget" direction="vertical" class="assign-target">
          <a-radio v-for="s in assignCandidates" :key="s.id" :value="s.id">{{ s.name }}</a-radio>
        </a-radio-group>
        <p v-if="assignCandidates.length === 0" class="ownership-empty">暂无可分配的启用销售</p>
        <div class="modal-footer">
          <a-button @click="assignVisible = false">取消</a-button>
          <a-button class="assign-confirm" type="primary" :loading="acting" @click="onAssignConfirm">
            确定分配
          </a-button>
        </div>
      </div>
    </a-modal>

    <!-- 转移弹窗（有归属线索 → 另一启用 Sales） -->
    <a-modal
      :visible="transferVisible"
      :render-to-body="false"
      :footer="false"
      title="转移线索"
      @cancel="transferVisible = false"
    >
      <div class="transfer-modal">
        <a-radio-group v-model="transferTarget" direction="vertical" class="transfer-target">
          <a-radio v-for="s in transferCandidates" :key="s.id" :value="s.id">{{ s.name }}</a-radio>
        </a-radio-group>
        <p v-if="transferCandidates.length === 0" class="ownership-empty">暂无可转移的启用销售</p>
        <div class="modal-footer">
          <a-button @click="transferVisible = false">取消</a-button>
          <a-button class="transfer-confirm" type="primary" :loading="acting" @click="onTransferConfirm">
            确定转移
          </a-button>
        </div>
      </div>
    </a-modal>
  </section>

  <a-spin v-else class="detail-loading" />
</template>

<style scoped>
.detail-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.detail-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  background: var(--dt-surface, #ffffff);
  border: 1px solid var(--dt-line, #e6e8f0);
  border-radius: var(--dt-radius, 12px);
  padding: 20px 24px;
}

.detail-title {
  margin: 0 0 4px;
  font-size: 20px;
  font-weight: 700;
  color: var(--dt-text, #202438);
}

.detail-sub {
  font-size: 13px;
  color: var(--dt-muted, #70778c);
}

.detail-desc,
.detail-progress {
  background: var(--dt-surface, #ffffff);
  border: 1px solid var(--dt-line, #e6e8f0);
  border-radius: var(--dt-radius, 12px);
  padding: 16px;
}

.detail-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  background: var(--dt-surface, #ffffff);
  border: 1px solid var(--dt-line, #e6e8f0);
  border-radius: var(--dt-radius, 12px);
  padding: 16px;
}

.progress-title {
  margin: 0 0 12px;
  font-size: 16px;
  font-weight: 700;
  color: var(--dt-text, #202438);
}

.progress-form {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-bottom: 16px;
}

.progress-submit {
  align-self: flex-start;
}

.progress-item-head {
  display: flex;
  gap: 12px;
  font-size: 13px;
  color: var(--dt-muted, #70778c);
}

.progress-item-content {
  margin-top: 4px;
  color: var(--dt-text, #202438);
}

.progress-empty {
  color: var(--dt-muted, #70778c);
}

.win-preview {
  margin: 8px 0;
  font-weight: 600;
  color: var(--dt-brand, #2563ff);
}

.win-modal,
.lose-modal,
.release-modal,
.assign-modal,
.transfer-modal {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.ownership-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  background: var(--dt-surface, #ffffff);
  border: 1px solid var(--dt-line, #e6e8f0);
  border-radius: var(--dt-radius, 12px);
  padding: 16px;
}

.ownership-label {
  font-size: 13px;
  font-weight: 600;
  color: var(--dt-muted, #70778c);
}

.ownership-empty {
  color: var(--dt-muted, #70778c);
}

.modal-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

.detail-loading {
  display: block;
  margin: 64px auto;
}
</style>
