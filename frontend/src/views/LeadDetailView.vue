<script setup lang="ts">
import { useRoute } from 'vue-router'
import LeadDetailPanel from '../components/LeadDetailPanel.vue'

/**
 * 线索详情路由页：薄壳，仅从路由取 id 并委派给可复用的 `LeadDetailPanel`
 * （详情逻辑与工作台抽屉共用，design D1）。
 */
const route = useRoute()
const leadId = Number(route.params.id)
</script>

<template>
  <LeadDetailPanel :lead-id="leadId" />
</template>
