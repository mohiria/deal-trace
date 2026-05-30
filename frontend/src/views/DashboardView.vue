<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { fetchDashboard } from '../api/dashboard'
import type { DashboardView } from '../api/dashboard'
import { formatLossRate, formatWonAmount } from '../utils/dashboard'

/**
 * 工作台首屏只读指标看板（spec frontend-workbench / PRD §7.12）。
 *
 * 首屏挂载即拉取 `GET /dashboard`，三态（loading / error / loaded）驱动渲染。
 * 口径由后端按登录角色裁决（不传视角参数）；金额与流失率经格式化纯函数呈现
 * （流失率空值 `--`、零值 `0%`；金额空集 `¥0`）。
 *
 * 鉴权失效（`UNAUTHORIZED`）不在本视图处理——既有响应拦截器统一清退到登录入口（design D4）；
 * 本视图仅处理 loading 与「非鉴权失败」可重试态。
 */
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

onMounted(load)
</script>

<template>
  <section class="dashboard">
    <header class="dashboard-head">
      <h2 class="dashboard-title">销售工作台</h2>
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
</template>

<style scoped>
.dashboard {
  background: var(--dt-surface, #ffffff);
  border: 1px solid var(--dt-line, #e6e8f0);
  border-radius: var(--dt-radius, 12px);
  box-shadow: var(--dt-shadow, 0 16px 40px rgba(36, 42, 66, 0.08));
  padding: 24px;
}

.dashboard-head {
  margin-bottom: 20px;
}

.dashboard-title {
  margin: 0;
  font-size: 20px;
  font-weight: 700;
  color: var(--dt-text, #202438);
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
</style>
