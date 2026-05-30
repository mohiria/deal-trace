<script setup lang="ts">
import { ref } from 'vue'
import { searchCustomers } from '../api/customers'
import type { CustomerView } from '../api/customers'

/**
 * 客户可搜索下拉选择器（spec R3 / design D4）。
 * 按客户名称 / USCI 关键词远程搜索既有客户供选中；仅选既有，**不**提供"边搜边建客户"捷径。
 * `v-model` 暴露选中的 customerId；同时 emit `select` 抛出完整客户对象（新建线索预检需 id、展示需 name/usci）。
 */
const props = withDefaults(
  defineProps<{
    modelValue: number | null
    /** 去抖毫秒数；测试可传 0 以即时触发。 */
    debounceMs?: number
  }>(),
  { debounceMs: 300 },
)

const emit = defineEmits<{
  (e: 'update:modelValue', value: number | null): void
  (e: 'select', customer: CustomerView): void
}>()

const keyword = ref('')
const options = ref<CustomerView[]>([])
const loading = ref(false)
const selectedLabel = ref('')
const open = ref(false)
let timer: ReturnType<typeof setTimeout> | null = null

async function runSearch(value: string) {
  loading.value = true
  try {
    options.value = await searchCustomers(value)
    open.value = true
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
  emit('select', customer)
  selectedLabel.value = `${customer.name}（${customer.usci}）`
  keyword.value = ''
  options.value = []
  open.value = false
}
</script>

<template>
  <div class="customer-select">
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
    <p v-else-if="open && !loading" class="cs-empty">无匹配客户</p>
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
</style>
