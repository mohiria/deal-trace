import type { Role } from '../stores/auth'

export interface NavEntry {
  label: string
  routeName: string
  /** 限定可见角色；省略表示所有已登录角色可见。 */
  roles?: Role[]
}

export interface NavSection {
  title: string
  entries: NavEntry[]
}

/**
 * 声明式导航表（D5）：显隐与路由可达共用同一份角色元数据。
 * shell 阶段业务入口指向占位路由，由后续 change 填充页面。
 */
export const navSections: NavSection[] = [
  {
    title: '工作区',
    entries: [
      { label: '销售工作台', routeName: 'workbench' },
      { label: '我的线索', routeName: 'my-leads' },
      { label: '公海线索', routeName: 'public-pool' },
      { label: '客户管理', routeName: 'customers' },
    ],
  },
  {
    title: '管理',
    entries: [
      { label: '合同记录', routeName: 'contracts' },
      { label: '用户管理', routeName: 'users', roles: ['ADMIN'] },
      // 系统日志入口暂不呈现：其查看能力尚无后端读端点，避免指向无数据来源的死链（frontend-admin）。
      // 待 system-log 查看能力交付后恢复 `{ label: '系统日志', routeName: 'system-logs', roles: ['ADMIN'] }`。
    ],
  },
]

/** 按角色过滤导航；空 section（条目全被过滤）不返回。 */
export function visibleSections(role: Role | null): NavSection[] {
  return navSections
    .map((section) => ({
      ...section,
      entries: section.entries.filter(
        (entry) => !entry.roles || (role !== null && entry.roles.includes(role)),
      ),
    }))
    .filter((section) => section.entries.length > 0)
}
