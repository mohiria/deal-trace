<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'

const route = useRoute()
const title = computed(() => route.meta.title ?? '工作台')
</script>

<template>
  <div class="placeholder">
    <h2 class="placeholder-title">{{ title }}</h2>
    <p class="placeholder-hint">该模块将由后续 change 实现。</p>
  </div>
</template>

<style scoped>
.placeholder {
  background: var(--dt-surface, #ffffff);
  border: 1px solid var(--dt-line, #e6e8f0);
  border-radius: var(--dt-radius, 12px);
  box-shadow: var(--dt-shadow, 0 16px 40px rgba(36, 42, 66, 0.08));
  padding: 40px 32px;
}

.placeholder-title {
  margin: 0 0 8px;
  font-size: 20px;
  font-weight: 700;
  color: var(--dt-text, #202438);
}

.placeholder-hint {
  margin: 0;
  color: var(--dt-muted, #70778c);
}
</style>
