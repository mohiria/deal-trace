import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { LeadView, PoolLeadView, ProgressLogView } from '../api/leads'
import {
  addProgress as apiAddProgress,
  assignLead as apiAssignLead,
  changeStage as apiChangeStage,
  claimLead as apiClaimLead,
  fetchAllLeads,
  fetchLead,
  fetchMyLeads,
  fetchPool,
  fetchProgress,
  loseLead as apiLoseLead,
  recallLead as apiRecallLead,
  releaseLead as apiReleaseLead,
  transferLead as apiTransferLead,
  winLead as apiWinLead,
} from '../api/leads'

/**
 * 线索 store（design D2）：承载列表 / 详情 / 进度，并维护跨视图联动
 * （认领移出公海、退回移出名下、追加进度置顶并刷新最后跟踪时间）。
 * 视图保持薄，写动作把 ApiError 透传给调用方按 code 分支（design D5），不在此吞掉。
 */
export const useLeadsStore = defineStore('leads', () => {
  const myLeads = ref<LeadView[]>([])
  const allLeads = ref<LeadView[]>([])
  const pool = ref<PoolLeadView[]>([])
  const currentLead = ref<LeadView | null>(null)
  const progress = ref<ProgressLogView[]>([])
  const loading = ref(false)
  const error = ref<string | null>(null)

  async function loadMyLeads() {
    loading.value = true
    error.value = null
    try {
      myLeads.value = await fetchMyLeads()
    } finally {
      loading.value = false
    }
  }

  async function loadAllLeads() {
    loading.value = true
    error.value = null
    try {
      allLeads.value = await fetchAllLeads()
    } finally {
      loading.value = false
    }
  }

  async function loadPool() {
    loading.value = true
    error.value = null
    try {
      pool.value = await fetchPool()
    } finally {
      loading.value = false
    }
  }

  async function loadLead(id: number) {
    loading.value = true
    error.value = null
    try {
      currentLead.value = await fetchLead(id)
    } catch (e) {
      // 加载失败清空当前详情，杜绝抽屉残留上一条线索数据（refine：抽屉切换不残留）。
      currentLead.value = null
      throw e
    } finally {
      loading.value = false
    }
  }

  /**
   * 以脱敏公海列表数据填充当前详情（refine：SALES 公海抽屉只读脱敏摘要）。
   * 公海线索 ownerSalesId 恒为 null；进度对非归属 SALES 不可见，置空。
   * 不调用明文详情端点，电话保持后端已脱敏值。
   */
  function setCurrentFromPool(p: PoolLeadView) {
    currentLead.value = {
      id: p.id,
      customerId: p.customerId,
      customerName: p.customerName,
      customerUsci: p.customerUsci,
      businessYear: p.businessYear,
      businessType: p.businessType,
      contactName: p.contactName,
      contactPhone: p.contactPhone,
      leadSource: p.leadSource,
      ownerSalesId: null,
      ownerSalesName: null,
      stage: p.stage,
      lastTrackedAt: p.lastTrackedAt,
      loseReason: null,
      loseNote: null,
      createdAt: p.createdAt,
      wonAt: null,
      lostAt: null,
    }
    progress.value = []
  }

  async function loadProgress(id: number) {
    progress.value = await fetchProgress(id)
  }

  /** 认领成功：线索移出公海（其余由调用方导航 / 刷新名下）。 */
  async function claim(id: number): Promise<LeadView> {
    const lead = await apiClaimLead(id)
    pool.value = pool.value.filter((l) => l.id !== id)
    return lead
  }

  /** 退回成功：线索移出名下；若为当前详情则刷新归属。 */
  async function release(id: number, releaseNote: string): Promise<LeadView> {
    const lead = await apiReleaseLead(id, releaseNote)
    myLeads.value = myLeads.value.filter((l) => l.id !== id)
    if (currentLead.value?.id === id) {
      currentLead.value = lead
    }
    return lead
  }

  /** 追加进度成功：新记录置顶倒序流，并刷新当前线索的最后跟踪时间。 */
  async function addLeadProgress(id: number, method: string, content: string): Promise<ProgressLogView> {
    const entry = await apiAddProgress(id, method, content)
    progress.value = [entry, ...progress.value]
    if (currentLead.value?.id === id) {
      currentLead.value = { ...currentLead.value, lastTrackedAt: entry.trackTime }
    }
    return entry
  }

  async function changeStage(id: number, stage: string): Promise<LeadView> {
    const lead = await apiChangeStage(id, stage)
    if (currentLead.value?.id === id) {
      currentLead.value = lead
    }
    return lead
  }

  async function winLead(id: number, contractAmount: string, signedDate: string): Promise<LeadView> {
    const lead = await apiWinLead(id, contractAmount, signedDate)
    if (currentLead.value?.id === id) {
      currentLead.value = lead
    }
    return lead
  }

  async function loseLead(id: number, loseReason: string, loseNote?: string): Promise<LeadView> {
    const lead = await apiLoseLead(id, loseReason, loseNote)
    if (currentLead.value?.id === id) {
      currentLead.value = lead
    }
    return lead
  }

  /** Admin 分配公海线索：刷新 currentLead 归属。 */
  async function assign(id: number, salesId: number): Promise<LeadView> {
    const lead = await apiAssignLead(id, salesId)
    if (currentLead.value?.id === id) {
      currentLead.value = lead
    }
    return lead
  }

  /** Admin 回收名下线索至公海：刷新 currentLead 归属。 */
  async function recall(id: number): Promise<LeadView> {
    const lead = await apiRecallLead(id)
    if (currentLead.value?.id === id) {
      currentLead.value = lead
    }
    return lead
  }

  /** Admin 转移名下线索给另一 Sales：刷新 currentLead 归属。 */
  async function transfer(id: number, salesId: number): Promise<LeadView> {
    const lead = await apiTransferLead(id, salesId)
    if (currentLead.value?.id === id) {
      currentLead.value = lead
    }
    return lead
  }

  return {
    myLeads,
    allLeads,
    pool,
    currentLead,
    progress,
    loading,
    error,
    loadMyLeads,
    loadAllLeads,
    loadPool,
    loadLead,
    setCurrentFromPool,
    loadProgress,
    claim,
    release,
    addLeadProgress,
    changeStage,
    winLead,
    loseLead,
    assign,
    recall,
    transfer,
  }
})
