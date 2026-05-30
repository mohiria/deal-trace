<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { useLeadsStore } from '../stores/leads'
import { visibleSections } from './navigation'
import CreateLeadModal from './CreateLeadModal.vue'

const router = useRouter()
const auth = useAuthStore()
const leads = useLeadsStore()
const emit = defineEmits<{ 'open-create-lead': [] }>()

const sections = computed(() => visibleSections(auth.role))
const displayName = computed(() => auth.currentUser?.name ?? '')
const createLeadVisible = ref(false)

function openCreateLead() {
  createLeadVisible.value = true
  emit('open-create-lead')
}

async function refreshLeadListsAfterCreate() {
  await Promise.allSettled([leads.loadMyLeads(), leads.loadPool(), auth.isAdmin ? leads.loadAllLeads() : Promise.resolve()])
}

async function onLogout() {
  auth.logout()
  await router.push({ name: 'login' })
}
</script>

<template>
  <div class="shell">
    <aside class="shell-sidebar">
      <div class="shell-brand">
        <span class="shell-mark" aria-hidden="true"></span>
        <div class="shell-brand-title">
          <strong>DealTrace</strong>
          <span>商迹 CRM 工作台</span>
        </div>
      </div>

      <nav class="shell-nav">
        <div v-for="section in sections" :key="section.title" class="shell-nav-section">
          <div class="shell-nav-label">{{ section.title }}</div>
          <RouterLink
            v-for="entry in section.entries"
            :key="entry.routeName"
            :to="{ name: entry.routeName }"
            class="shell-nav-item"
            active-class="is-active"
          >
            <span class="shell-nav-dot" aria-hidden="true"></span>
            <span>{{ entry.label }}</span>
          </RouterLink>
        </div>
      </nav>

      <section class="shell-reminder" aria-label="今日提醒">
        <h3>今日提醒</h3>
        <p>优先处理高价值公海线索和长期未跟进客户。</p>
        <button class="shell-reminder-action" data-test="reminder-create-lead" type="button" @click="openCreateLead">
          新增线索
        </button>
      </section>
    </aside>

    <main class="shell-main">
      <header class="shell-topbar">
        <div class="shell-user">
          <span class="shell-user-name">{{ displayName }}</span>
        </div>
        <a-button class="shell-logout" @click="onLogout">退出登录</a-button>
      </header>

      <section class="shell-content">
        <RouterView />
      </section>
    </main>
    <CreateLeadModal v-model:visible="createLeadVisible" @created="refreshLeadListsAfterCreate" />
  </div>
</template>

<style scoped>
.shell {
  display: grid;
  grid-template-columns: 248px 1fr;
  min-height: 100vh;
  background: var(--dt-bg, #f6f7fb);
}

.shell-sidebar {
  background: var(--dt-surface, #ffffff);
  border-right: 1px solid var(--dt-line, #e6e8f0);
  padding: 20px 14px;
  position: sticky;
  top: 0;
  height: 100vh;
  overflow: auto;
}

.shell-brand {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 10px 22px;
}

.shell-mark {
  width: 34px;
  height: 34px;
  border-radius: 10px;
  background: linear-gradient(135deg, var(--dt-brand, #2563ff), var(--dt-green, #0f9f6e));
  position: relative;
  box-shadow: 0 10px 24px rgba(37, 99, 255, 0.22);
  flex: 0 0 auto;
}

.shell-mark::after {
  content: "";
  position: absolute;
  width: 12px;
  height: 12px;
  border: 3px solid #fff;
  border-left: 0;
  border-bottom: 0;
  border-radius: 2px;
  transform: rotate(45deg);
  top: 9px;
  left: 8px;
}

.shell-brand-title strong {
  display: block;
  font-size: 17px;
  font-weight: 800;
  color: var(--dt-text, #202438);
}

.shell-brand-title span {
  font-size: 12px;
  color: var(--dt-muted, #70778c);
}

.shell-nav-section {
  margin: 18px 0;
}

.shell-nav-label {
  color: var(--dt-muted-2, #9aa1b4);
  font-size: 12px;
  font-weight: 700;
  padding: 0 12px 8px;
  text-transform: uppercase;
}

.shell-nav-item {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 38px;
  padding: 0 12px;
  margin: 3px 0;
  border-radius: var(--dt-radius-sm, 8px);
  color: #485067;
  text-decoration: none;
  font-size: 14px;
  font-weight: 600;
}

.shell-nav-item:hover {
  background: var(--dt-surface-soft, #f8f9fd);
}

.shell-nav-item.is-active {
  background: var(--dt-brand-soft, #eaf0ff);
  color: #1749d5;
}

.shell-nav-dot {
  width: 10px;
  height: 10px;
  border-radius: 4px;
  background: var(--dt-line-strong, #d5d9e6);
  flex: 0 0 auto;
}

.shell-nav-item.is-active .shell-nav-dot {
  background: var(--dt-brand, #2563ff);
}

.shell-reminder {
  margin: 22px 8px 0;
  padding: 14px;
  border-radius: var(--dt-radius, 12px);
  background: #f4f7ff;
  border: 1px solid #dce6ff;
}

.shell-reminder h3 {
  margin: 0 0 6px;
  font-size: 14px;
  color: var(--dt-text, #202438);
}

.shell-reminder p {
  margin: 0 0 12px;
  color: var(--dt-muted, #70778c);
  font-size: 12px;
  line-height: 1.6;
}

.shell-reminder-action {
  min-height: 34px;
  padding: 0 12px;
  display: inline-flex;
  align-items: center;
  border: 0;
  border-radius: 8px;
  background: var(--dt-brand, #2563ff);
  color: #fff;
  font-weight: 800;
  font-size: 13px;
  box-shadow: 0 8px 18px rgba(37, 99, 255, 0.22);
  cursor: pointer;
}

.shell-main {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.shell-topbar {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 16px;
  padding: 16px 28px;
  background: var(--dt-surface, #ffffff);
  border-bottom: 1px solid var(--dt-line, #e6e8f0);
}

.shell-user {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  line-height: 1.2;
}

.shell-user-name {
  font-weight: 600;
  color: var(--dt-text, #202438);
}

.shell-content {
  flex: 1;
  padding: 24px 28px;
}
</style>
