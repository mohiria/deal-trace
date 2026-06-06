<script setup lang="ts">
import { ref, watch } from 'vue'
import { searchCustomers } from '../api/customers'
import type { CustomerView } from '../api/customers'

/** 内联录入的新客户（名称 + USCI）；USCI 归一化 / 校验由后端权威完成，前端不复算。 */
export interface NewCustomerInput {
  name: string
  usci: string
}

/**
 * 客户可搜索下拉选择器（spec「通过可搜索下拉选择既有客户」，本 change MODIFIED）。
 * 按客户名称 / USCI 关键词远程搜索既有客户供选中；**候选为空时**提供"录入新客户"入口
 * （名称 + USCI），交由后端在建线索同一事务内 find-or-create——前端不自行先建客户、不复算 USCI 校验。
 * `v-model`（modelValue）暴露选中的 customerId；`v-model:newCustomer` 暴露内联录入的新客户；
 * 同时 emit `select` 抛出完整客户对象（既有客户预检需 id、展示需 name/usci）。
 */
const props = withDefaults(
  defineProps<{
    modelValue: number | null
    /** 内联录入的新客户；未处于录入态时为 null。 */
    newCustomer?: NewCustomerInput | null
    /** 去抖毫秒数；测试可传 0 以即时触发。 */
    debounceMs?: number
  }>(),
  { newCustomer: null, debounceMs: 300 },
)

const emit = defineEmits<{
  (e: 'update:modelValue', value: number | null): void
  (e: 'update:newCustomer', value: NewCustomerInput | null): void
  (e: 'select', customer: CustomerView): void
}>()

const keyword = ref('')
const options = ref<CustomerView[]>([])
const loading = ref(false)
const searched = ref(false)
const selectedLabel = ref('')
const open = ref(false)
const mode = ref<'search' | 'new'>('search')
const newName = ref('')
const newUsci = ref('')
let timer: ReturnType<typeof setTimeout> | null = null

async function runSearch(value: string) {
  loading.value = true
  try {
    options.value = (await searchCustomers({ keyword: value })).items
    open.value = true
    searched.value = true
  } catch {
    options.value = []
  } finally {
    loading.value = false
  }
}

function onInput() {
  if (timer !== null) {
    clearTimeout(timer)
  }
  timer = setTimeout(() => {
    void runSearch(keyword.value)
  }, props.debounceMs)
}

function choose(customer: CustomerView) {
  emit('update:modelValue', customer.id)
  emit('update:newCustomer', null)
  emit('select', customer)
  selectedLabel.value = `${customer.name}（${customer.usci}）`
  keyword.value = ''
  options.value = []
  open.value = false
}

/** 候选为空时进入"录入新客户"态：以当前关键词预填名称，清掉既有客户选择。 */
function enterNewMode() {
  mode.value = 'new'
  newName.value = keyword.value.trim()
  newUsci.value = ''
  selectedLabel.value = ''
  emit('update:modelValue', null)
  emit('update:newCustomer', { name: newName.value, usci: '' })
}

function onNewInput() {
  emit('update:newCustomer', { name: newName.value.trim(), usci: newUsci.value.trim() })
}

/** 退出录入态，改用搜索选既有客户。 */
function backToSearch() {
  mode.value = 'search'
  emit('update:newCustomer', null)
}

// 受控同步：父层把 modelValue 外部重置为 null（如弹窗重开 resetForm）时，清掉内部展示状态，
// 避免"显示已选客户 A、提交却提示未选客户"的残留不一致。
watch(
  () => props.modelValue,
  (value) => {
    if (value == null) {
      selectedLabel.value = ''
      keyword.value = ''
      options.value = []
      open.value = false
      searched.value = false
    }
  },
)

// 父层把 newCustomer 外部重置为 null（resetForm）时回到搜索态并清空录入框。
watch(
  () => props.newCustomer,
  (value) => {
    if (value == null) {
      mode.value = 'search'
      newName.value = ''
      newUsci.value = ''
    }
  },
)
</script>

<template>
  <div class="customer-select">
    <template v-if="mode === 'search'">
      <input
        v-model="keyword"
        class="cs-search"
        type="text"
        placeholder="按客户名称或统一社会信用代码搜索"
        autocomplete="off"
        @input="onInput"
      />
      <p v-if="selectedLabel" class="cs-selected">已选客户：{{ selectedLabel }}</p>
      <ul v-if="open && options.length > 0" class="cs-options">
        <li
          v-for="opt in options"
          :key="opt.id"
          class="cs-option"
          @click="choose(opt)"
        >
          <span class="cs-option-name">{{ opt.name }}</span>
          <span class="cs-option-usci">{{ opt.usci }}</span>
        </li>
      </ul>
      <div v-else-if="open && !loading && searched" class="cs-empty-block">
        <p class="cs-empty">无匹配客户</p>
        <button type="button" class="cs-create-new" @click="enterNewMode">
          录入新客户「{{ keyword.trim() }}」
        </button>
      </div>
    </template>

    <div v-else class="cs-new-form">
      <p class="cs-new-title">录入新客户（名称 + 统一社会信用代码）</p>
      <input
        v-model="newName"
        class="cs-new-name"
        type="text"
        placeholder="客户名称（必填）"
        autocomplete="off"
        @input="onNewInput"
      />
      <input
        v-model="newUsci"
        class="cs-new-usci"
        type="text"
        placeholder="18 位统一社会信用代码（必填）"
        autocomplete="off"
        @input="onNewInput"
      />
      <button type="button" class="cs-back-to-search" @click="backToSearch">改用搜索选择既有客户</button>
    </div>
  </div>
</template>

<style scoped>
.customer-select {
  position: relative;
}

.cs-search {
  width: 100%;
  box-sizing: border-box;
  padding: 8px 12px;
  border: 1px solid var(--dt-line, #e6e8f0);
  border-radius: var(--dt-radius-sm, 6px);
  font-size: 14px;
  color: var(--dt-text, #202438);
}

.cs-search:focus {
  outline: none;
  border-color: var(--dt-primary, #3b6cff);
}

.cs-selected {
  margin: 6px 0 0;
  font-size: 13px;
  color: var(--dt-muted, #70778c);
}

.cs-options {
  list-style: none;
  margin: 4px 0 0;
  padding: 4px;
  border: 1px solid var(--dt-line, #e6e8f0);
  border-radius: var(--dt-radius-sm, 6px);
  background: var(--dt-surface, #ffffff);
  box-shadow: var(--dt-shadow, 0 16px 40px rgba(36, 42, 66, 0.08));
  max-height: 240px;
  overflow-y: auto;
}

.cs-option {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  padding: 8px 12px;
  border-radius: var(--dt-radius-sm, 6px);
  cursor: pointer;
}

.cs-option:hover {
  background: var(--dt-hover, #f2f5ff);
}

.cs-option-usci {
  color: var(--dt-muted, #70778c);
  font-size: 13px;
}

.cs-empty {
  margin: 4px 0 0;
  padding: 8px 12px;
  font-size: 13px;
  color: var(--dt-muted, #70778c);
}

.cs-empty-block {
  margin: 4px 0 0;
}

.cs-create-new {
  display: inline-block;
  margin: 0 12px 4px;
  padding: 6px 12px;
  border: 1px dashed var(--dt-primary, #3b6cff);
  border-radius: var(--dt-radius-sm, 6px);
  background: transparent;
  color: var(--dt-primary, #3b6cff);
  font-size: 13px;
  cursor: pointer;
}

.cs-new-form {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.cs-new-title {
  margin: 0;
  font-size: 13px;
  font-weight: 600;
  color: var(--dt-text, #202438);
}

.cs-new-name,
.cs-new-usci {
  width: 100%;
  box-sizing: border-box;
  padding: 8px 12px;
  border: 1px solid var(--dt-line, #e6e8f0);
  border-radius: var(--dt-radius-sm, 6px);
  font-size: 14px;
  color: var(--dt-text, #202438);
}

.cs-back-to-search {
  align-self: flex-start;
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--dt-primary, #3b6cff);
  font-size: 13px;
  cursor: pointer;
}
</style>
