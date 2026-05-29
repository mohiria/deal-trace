<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { Message } from '@arco-design/web-vue'
import type { TableColumnData } from '@arco-design/web-vue'
import { useAuthStore } from '../stores/auth'
import { useLeadsStore } from '../stores/leads'
import { ApiError } from '../api/client'
import type { PoolLeadView } from '../api/leads'

/**
 * 公海线索（spec R2）：列表展示后端返回的电话（Sales 脱敏 / Admin 明文）；
 * Sales 可认领，认领成功后线索移出公海；并发冲突（LEAD_ALREADY_CLAIMED）提示并刷新。
 */
const auth = useAuthStore()
const leads = useLeadsStore()
const claimingId = ref<number | null>(null)

const columns: TableColumnData[] = [
  { title: '客户', dataIndex: 'customerName' },
  { title: '业务类型', dataIndex: 'businessType' },
  { title: '年度', dataIndex: 'businessYear' },
  { title: '联系人', dataIndex: 'contactName' },
  { title: '联系电话', dataIndex: 'contactPhone' },
  { title: '阶段', dataIndex: 'stage' },
  ...(auth.isAdmin ? [] : [{ title: '操作', slotName: 'operations' } as TableColumnData]),
]

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

onMounted(() => {
  void leads.loadPool()
})
</script>

<template>
  <section class="pool-page">
    <header class="pool-head">
      <h2 class="pool-title">公海线索</h2>
      <p v-if="!auth.isAdmin" class="pool-hint">联系电话脱敏展示，认领后可见完整号码。</p>
    </header>

    <a-table
      :data="leads.pool"
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
  margin-bottom: 16px;
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

.pool-empty {
  padding: 24px;
  text-align: center;
  color: var(--dt-muted, #70778c);
}
</style>
