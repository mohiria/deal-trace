<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { visibleSections } from './navigation'

const router = useRouter()
const auth = useAuthStore()

const sections = computed(() => visibleSections(auth.role))
const displayName = computed(() => auth.currentUser?.name ?? '')
const roleLabel = computed(() => (auth.isAdmin ? '管理员' : '销售'))

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
            {{ entry.label }}
          </RouterLink>
        </div>
      </nav>
    </aside>

    <main class="shell-main">
      <header class="shell-topbar">
        <div class="shell-user">
          <span class="shell-user-name">{{ displayName }}</span>
          <span class="shell-user-role">{{ roleLabel }}</span>
        </div>
        <a-button class="shell-logout" @click="onLogout">退出登录</a-button>
      </header>

      <section class="shell-content">
        <RouterView />
      </section>
    </main>
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
  padding: 0 10px 8px;
  text-transform: uppercase;
  letter-spacing: 0.04em;
}

.shell-nav-item {
  display: block;
  padding: 9px 12px;
  border-radius: var(--dt-radius-sm, 8px);
  color: var(--dt-text, #202438);
  text-decoration: none;
  font-size: 14px;
}

.shell-nav-item:hover {
  background: var(--dt-surface-soft, #f8f9fd);
}

.shell-nav-item.is-active {
  background: var(--dt-brand-soft, #eaf0ff);
  color: var(--dt-brand, #2563ff);
  font-weight: 600;
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

.shell-user-role {
  font-size: 12px;
  color: var(--dt-muted, #70778c);
}

.shell-content {
  flex: 1;
  padding: 24px 28px;
}
</style>
