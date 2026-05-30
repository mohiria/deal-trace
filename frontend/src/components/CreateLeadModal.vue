<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { Message } from '@arco-design/web-vue'
import { ApiError } from '../api/client'
import { createLead, duplicateCheck } from '../api/leads'
import type { DuplicateCheckResult } from '../api/leads'
import { useAuthStore } from '../stores/auth'
import { BUSINESS_TYPES, isValidContactPhone } from '../utils/lead'
import type { CustomerView } from '../api/customers'
import CustomerSelect from './CustomerSelect.vue'

const props = withDefaults(defineProps<{ visible: boolean; debounceMs?: number }>(), { debounceMs: 300 })
const emit = defineEmits<{
  'update:visible': [value: boolean]
  created: []
}>()

const auth = useAuthStore()

const modalVisible = computed({
  get: () => props.visible,
  set: (value: boolean) => emit('update:visible', value),
})

const selectedCustomerId = ref<number | null>(null)
const selectedCustomer = ref<CustomerView | null>(null)
const leadForm = ref({ businessType: '', contactName: '', contactPhone: '', leadSource: '' })
const ownerMode = ref<'self' | 'pool'>('self')
const dupResult = ref<DuplicateCheckResult | null>(null)
const submittingLead = ref(false)

function resetForm() {
  selectedCustomerId.value = null
  selectedCustomer.value = null
  leadForm.value = { businessType: '', contactName: '', contactPhone: '', leadSource: '' }
  ownerMode.value = 'self'
  dupResult.value = null
}

watch(
  () => props.visible,
  (visible) => {
    if (visible) {
      resetForm()
    }
  },
)

function onCustomerSelected(customer: CustomerView) {
  selectedCustomer.value = customer
}

watch(
  () => [selectedCustomerId.value, leadForm.value.businessType] as const,
  async ([id, type]) => {
    if (id == null || !type) {
      dupResult.value = null
      return
    }
    try {
      dupResult.value = await duplicateCheck(id, type)
    } catch (error) {
      dupResult.value = null
      if (error instanceof ApiError) {
        Message.error(error.message)
      }
    }
  },
)

function blockingMessage(reason: string | null): string {
  if (reason === 'DUPLICATE_ACTIVE_LEAD') {
    return '该客户在本年度该业务类型下已有进行中线索，不可重复创建。'
  }
  if (reason === 'DUPLICATE_WON_LEAD') {
    return '该客户在本年度该业务类型下已有已赢单线索，不可重复创建。'
  }
  return '该业务线索不可重复创建。'
}

async function onCreateLead() {
  if (selectedCustomerId.value == null) {
    Message.warning('请先选择客户')
    return
  }
  if (!leadForm.value.businessType) {
    Message.warning('请选择业务类型')
    return
  }
  if (leadForm.value.contactName.trim() === '') {
    Message.warning('请填写联系人')
    return
  }
  if (!isValidContactPhone(leadForm.value.contactPhone)) {
    Message.warning('联系电话格式不正确')
    return
  }
  if (dupResult.value && dupResult.value.canCreate === false) {
    Message.warning(blockingMessage(dupResult.value.blockingReason))
    return
  }

  submittingLead.value = true
  try {
    const source = leadForm.value.leadSource.trim()
    await createLead({
      customerId: selectedCustomerId.value,
      businessType: leadForm.value.businessType,
      contactName: leadForm.value.contactName.trim(),
      contactPhone: leadForm.value.contactPhone.trim(),
      ...(source ? { leadSource: source } : {}),
      ...(!auth.isAdmin && ownerMode.value === 'pool' ? { assignToPool: true } : {}),
    })
    modalVisible.value = false
    Message.success('线索创建成功')
    emit('created')
  } catch (error) {
    if (error instanceof ApiError) {
      Message.error(error.message)
    } else {
      Message.error('创建失败，请稍后重试')
    }
  } finally {
    submittingLead.value = false
  }
}
</script>

<template>
  <a-modal
    v-model:visible="modalVisible"
    data-test="create-lead-modal"
    title="新建业务线索"
    :render-to-body="false"
    :footer="false"
  >
    <a-form :model="leadForm" layout="vertical" @submit.prevent>
      <a-form-item label="客户" required>
        <CustomerSelect v-model="selectedCustomerId" :debounce-ms="props.debounceMs" @select="onCustomerSelected" />
      </a-form-item>
      <a-form-item label="业务类型" required>
        <a-radio-group v-model="leadForm.businessType" class="lead-type">
          <a-radio v-for="type in BUSINESS_TYPES" :key="type" :value="type">{{ type }}</a-radio>
        </a-radio-group>
      </a-form-item>

      <div v-if="dupResult && dupResult.canCreate === false" class="lead-block">
        {{ blockingMessage(dupResult.blockingReason) }}
      </div>

      <div v-if="dupResult && dupResult.historicalLost.length > 0" class="historical-lost">
        <p class="historical-lost-title">该客户该业务类型历史流失记录：</p>
        <ul>
          <li v-for="(entry, index) in dupResult.historicalLost" :key="index" class="historical-lost-item">
            <span class="hl-time">{{ entry.lostAt }}</span>
            <span class="hl-reason">{{ entry.loseReason }}</span>
            <span v-if="entry.loseNote" class="hl-note">{{ entry.loseNote }}</span>
          </li>
        </ul>
      </div>

      <a-form-item label="联系人" required>
        <a-input v-model="leadForm.contactName" class="lead-contact-name" placeholder="联系人姓名（必填）" />
      </a-form-item>
      <a-form-item label="联系电话" required>
        <a-input v-model="leadForm.contactPhone" class="lead-contact-phone" placeholder="手机号或座机（必填）" />
      </a-form-item>
      <a-form-item label="线索来源">
        <a-input v-model="leadForm.leadSource" class="lead-source" placeholder="线索来源（选填）" />
      </a-form-item>
      <a-form-item v-if="!auth.isAdmin" label="归属">
        <a-radio-group v-model="ownerMode" class="lead-owner">
          <a-radio value="self">归属自己</a-radio>
          <a-radio value="pool">放入公海</a-radio>
        </a-radio-group>
      </a-form-item>
    </a-form>
    <div class="lead-footer">
      <a-button @click="modalVisible = false">取消</a-button>
      <a-button class="lead-confirm" type="primary" :loading="submittingLead" @click="onCreateLead">创建线索</a-button>
    </div>
  </a-modal>
</template>

<style scoped>
.lead-block {
  margin: 8px 0;
  padding: 8px 12px;
  border-radius: var(--dt-radius-sm, 6px);
  background: var(--dt-danger-bg, #fff1f0);
  color: var(--dt-danger, #c0341d);
  font-size: 13px;
}

.historical-lost {
  margin: 8px 0;
  padding: 8px 12px;
  border-radius: var(--dt-radius-sm, 6px);
  background: var(--dt-warning-bg, #fffaf0);
  color: var(--dt-muted, #70778c);
  font-size: 13px;
}

.historical-lost-title {
  margin: 0 0 4px;
  font-weight: 600;
}

.historical-lost ul {
  margin: 0;
  padding-left: 18px;
}

.historical-lost-item {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
}

.lead-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 12px;
}
</style>
