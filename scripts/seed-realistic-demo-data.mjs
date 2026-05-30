#!/usr/bin/env node
import { spawnSync } from 'node:child_process'
import { writeFileSync } from 'node:fs'

const API_BASE = (process.env.DEALTRACE_API_BASE ?? 'http://114.132.164.71:8080/api').replace(/\/$/, '')
const ADMIN_EMAIL = process.env.DEALTRACE_ADMIN_EMAIL ?? 'admin@dealtrace.local'
const ADMIN_PASSWORD = process.env.DEALTRACE_ADMIN_PASSWORD
const SALES_PASSWORD = process.env.DEALTRACE_SEED_SALES_PASSWORD ?? 'Sales@2026!'
const CLEANUP_MODE = process.env.DEALTRACE_SEED_CLEANUP_MODE ?? 'sql-file'
const CLEANUP_SQL_PATH = process.env.DEALTRACE_SEED_CLEANUP_SQL ?? 'seed-cleanup.sql'
const TOTAL_CUSTOMERS = Number.parseInt(process.env.DEALTRACE_SEED_CUSTOMERS ?? '126', 10)
const TOTAL_LEADS = Number.parseInt(process.env.DEALTRACE_SEED_LEADS ?? '126', 10)

const businessTypes = ['BIM咨询', 'BIM培训', '定制开发']
const activeStages = ['未触达', '初步沟通', '方案报价', '商务谈判']
const trackMethods = ['电话', '微信', '拜访', '其他']
const loseReasons = ['价格过高', '选择竞品', '无明确需求', '联系不上', '其他']

const salesProfiles = [
  ['chen.wei', '陈伟', '华东大客户经理'],
  ['li.na', '李娜', '行业解决方案顾问'],
  ['wang.qiang', '王强', '华北区域销售'],
  ['zhao.min', '赵敏', '教育培训客户经理'],
  ['liu.yang', '刘洋', '制造行业销售'],
  ['sun.jie', '孙洁', '华南渠道经理'],
  ['zhou.hao', '周昊', '地产与园区客户经理'],
  ['wu.qian', '吴倩', '设计院客户顾问'],
  ['zheng.lei', '郑磊', '软件定制销售'],
  ['gao.yu', '高宇', '战略客户经理'],
]

const companyPrefixes = [
  '上海', '南京', '杭州', '苏州', '宁波', '合肥', '济南', '青岛', '北京', '天津',
  '石家庄', '郑州', '武汉', '长沙', '广州', '深圳', '佛山', '厦门', '成都', '重庆',
  '西安', '昆明', '南昌', '福州', '无锡', '常州', '嘉兴', '南通', '徐州', '太原',
]
const companyBodies = [
  '城建规划设计', '华筑工程咨询', '中科建筑科技', '鼎衡造价咨询', '远景产业园运营',
  '启明职业技术学院', '宏远建设集团', '天成装配式建筑', '睿联数字工程', '诚达地产开发',
  '智造装备集团', '轨道交通设计', '同创建筑设计院', '云帆信息科技', '博越工程管理',
  '盛景文旅开发', '新城基础设施投资', '安筑消防工程', '科源节能科技', '领航培训中心',
  '城市更新投资', '德信总承包', '清源水务工程', '联拓机电安装', '广厦幕墙工程',
]
const companySuffixes = ['有限公司', '股份有限公司', '集团有限公司', '研究院有限公司', '工程技术有限公司']

const contactSurnames = ['陈', '李', '王', '赵', '刘', '孙', '周', '吴', '郑', '高', '胡', '林', '何', '郭', '马', '罗', '梁', '宋']
const contactGivenNames = ['明轩', '雅婷', '子涵', '浩然', '思远', '雨桐', '嘉豪', '佳琪', '俊杰', '依琳', '博文', '晓峰', '静怡', '晨曦', '志强', '雪梅']
const titlesByType = {
  'BIM咨询': ['BIM中心主任', '项目经理', '设计院副总工', '工程技术负责人', '数字建造负责人'],
  'BIM培训': ['培训负责人', '教务主任', '人力资源经理', '学院实训中心主任', '技术培训主管'],
  '定制开发': ['信息化主管', '数字化转型负责人', 'IT经理', '业务系统负责人', '运营管理总监'],
}
const leadSources = ['老客户转介绍', '官网咨询', '行业会议', '招投标信息', '公众号留资', '合作伙伴推荐', '电话拜访', '园区沙龙', '设计院交流会']

const progressTemplates = {
  '未触达': [
    '已通过公开渠道确认联系人信息，计划本周内首次电话沟通。',
    '客户近期有相关项目备案，先整理同类型案例后再联系。',
  ],
  '初步沟通': [
    '已完成首次沟通，客户关注实施周期和团队驻场安排，约定下周补充需求清单。',
    '客户认可方向，但需要内部确认预算口径，后续跟进采购和技术两条线。',
    '对方希望先看同行案例，已发送两份案例材料并约定三天后回访。',
  ],
  '方案报价': [
    '已根据项目范围提交初版方案和报价，客户要求拆分软件、服务和培训明细。',
    '技术负责人反馈方案可行，商务侧希望增加付款节点说明。',
    '客户正在比较两家供应商，下一步安排线上答疑会说明交付优势。',
  ],
  '商务谈判': [
    '采购部门已介入，正在确认合同条款、验收节点和发票类型。',
    '客户希望本月底前定供应商，已申请商务折扣并补充实施排期。',
    '已完成二轮谈判，金额基本确认，等待客户法务审核合同文本。',
  ],
}
const lostNotes = [
  '客户本年度项目预算冻结，计划下半年重新评估。',
  '客户临时调整建设计划，当前阶段不再采购外部服务。',
  '采购周期拉长且需求边界不清晰，暂不进入正式报价。',
  '对方集团已有统一供应商，本次不再单独采购。',
]

function usage() {
  console.log(`
DealTrace realistic demo data seeder

Required for API seeding:
  DEALTRACE_API_BASE=http://114.132.164.71:8080/api
  DEALTRACE_ADMIN_EMAIL=admin@dealtrace.local
  DEALTRACE_ADMIN_PASSWORD=...

Cleanup modes:
  DEALTRACE_SEED_CLEANUP_MODE=sql-file   Write cleanup SQL only. This is the default.
  DEALTRACE_SEED_CLEANUP_MODE=mysql      Execute cleanup through local mysql client.
  DEALTRACE_SEED_CLEANUP_MODE=docker     Execute cleanup through docker run mysql:8.4.
  DEALTRACE_SEED_CLEANUP_MODE=skip       Skip cleanup. Use only after manual cleanup.

DB variables for mysql/docker cleanup:
  DB_HOST=...
  DB_PORT=3306
  DB_USER=...
  DB_PASSWORD=...
`)
}

function cleanupSql() {
  return [
    'SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;',
    'SET FOREIGN_KEY_CHECKS = 0;',
    'DELETE FROM progress_log;',
    'DELETE FROM contract;',
    'DELETE FROM `lead`;',
    'DELETE FROM customer;',
    'DELETE FROM system_log;',
    "DELETE FROM account WHERE role = 'SALES';",
    'SET FOREIGN_KEY_CHECKS = 1;',
    '',
  ].join('\n')
}

function runCleanup() {
  const sql = cleanupSql()
  writeFileSync(CLEANUP_SQL_PATH, sql, { encoding: 'utf8' })

  if (CLEANUP_MODE === 'skip') {
    console.log(`[cleanup] skipped by DEALTRACE_SEED_CLEANUP_MODE=skip`)
    return
  }
  if (CLEANUP_MODE === 'sql-file') {
    console.log(`[cleanup] wrote ${CLEANUP_SQL_PATH}; execute it before seeding, then rerun with DEALTRACE_SEED_CLEANUP_MODE=skip`)
    process.exit(2)
  }

  const dbHost = requireEnv('DB_HOST')
  const dbPort = process.env.DB_PORT ?? '3306'
  const dbUser = requireEnv('DB_USER')
  const dbPassword = requireEnv('DB_PASSWORD')

  const command =
    CLEANUP_MODE === 'docker'
      ? {
          file: 'docker',
          args: [
            'run', '--rm', '-i',
            '-e', `MYSQL_PWD=${dbPassword}`,
            'mysql:8.4',
            'mysql',
            '--default-character-set=utf8mb4',
            '-h', dbHost,
            '-P', dbPort,
            '-u', dbUser,
            'dealtrace',
          ],
        }
      : {
          file: 'mysql',
          args: [
            '--default-character-set=utf8mb4',
            '-h', dbHost,
            '-P', dbPort,
            '-u', dbUser,
            `-p${dbPassword}`,
            'dealtrace',
          ],
        }

  const result = spawnSync(command.file, command.args, {
    input: sql,
    encoding: 'utf8',
    stdio: ['pipe', 'pipe', 'pipe'],
  })
  if (result.status !== 0) {
    throw new Error(`[cleanup] failed: ${result.stderr || result.stdout}`)
  }
  console.log('[cleanup] completed')
}

async function main() {
  if (process.argv.includes('--help')) {
    usage()
    return
  }
  if (process.argv.includes('--dry-run')) {
    dryRun()
    return
  }

  runCleanup()

  if (!ADMIN_PASSWORD) {
    throw new Error('DEALTRACE_ADMIN_PASSWORD is required for API seeding')
  }
  const admin = await login(ADMIN_EMAIL, ADMIN_PASSWORD)
  console.log(`[api] admin login ok: ${ADMIN_EMAIL}`)

  const sales = []
  for (let i = 0; i < salesProfiles.length; i += 1) {
    const [mailbox, name, position] = salesProfiles[i]
    const account = await post('/admin/accounts', admin.token, {
      email: `${mailbox}@dealtrace.local`,
      name: `${name}（${position}）`,
      password: SALES_PASSWORD,
      role: 'SALES',
    })
    sales.push({ ...account, plainName: name, position, password: SALES_PASSWORD })
  }
  console.log(`[api] sales created: ${sales.length}`)

  const salesTokens = new Map()
  for (const s of sales) {
    const session = await login(s.email, s.password)
    salesTokens.set(s.id, session.token)
  }

  const customers = []
  for (let i = 0; i < TOTAL_CUSTOMERS; i += 1) {
    const customer = await post('/customers', admin.token, {
      name: companyName(i),
      usci: generateUsci(i + 1000),
    })
    customers.push(customer)
  }
  console.log(`[api] customers created: ${customers.length}`)

  const leads = []
  for (let i = 0; i < TOTAL_LEADS; i += 1) {
    const businessType = businessTypes[i % businessTypes.length]
    const contact = contactFor(i, businessType)
    const owner = ownerFor(i, sales)
    const lead = await post('/leads', admin.token, {
      customerId: customers[i % customers.length].id,
      businessType,
      contactName: `${contact.name}（${contact.title}）`,
      contactPhone: phoneFor(i),
      leadSource: leadSources[i % leadSources.length],
      ownerSalesId: owner?.id ?? null,
      assignToPool: owner ? false : true,
    })
    leads.push({ ...lead, seedOwner: owner, seedBusinessType: businessType, seedContact: contact })
  }
  console.log(`[api] leads created: ${leads.length}`)

  const finalized = { won: 0, lost: 0, progress: 0 }
  for (let i = 0; i < leads.length; i += 1) {
    const lead = leads[i]
    const owner = lead.seedOwner
    const target = stageFor(i)

    if (owner) {
      const token = salesTokens.get(owner.id)
      const beforeCloseStage = target === '已赢单' || target === '已流失' ? activeStages[2 + (i % 2)] : target
      if (beforeCloseStage !== '未触达') {
        await patch(`/leads/${lead.id}/stage`, admin.token, { stage: beforeCloseStage })
      }
      finalized.progress += await addProgressHistory(lead, owner, token, beforeCloseStage, i)
      if (target === '已赢单') {
        await post(`/leads/${lead.id}/win`, admin.token, {
          contractAmount: contractAmount(lead.seedBusinessType, i),
          signedDate: signedDateFor(i),
        })
        finalized.won += 1
      } else if (target === '已流失') {
        const reason = loseReasons[i % loseReasons.length]
        await post(`/leads/${lead.id}/lose`, admin.token, {
          loseReason: reason,
          loseNote: reason === '其他' ? lostNotes[i % lostNotes.length] : null,
        })
        finalized.lost += 1
      }
    } else if (target !== '未触达' && target !== '已赢单' && target !== '已流失') {
      await patch(`/leads/${lead.id}/stage`, admin.token, { stage: target })
    }
  }

  console.log(`[api] progress logs created: ${finalized.progress}`)
  console.log(`[api] won leads/contracts: ${finalized.won}`)
  console.log(`[api] lost leads: ${finalized.lost}`)

  const all = await get('/leads', admin.token)
  console.log(`[verify] admin list returned ${all.length} recent leads`)
  const pool = await get('/leads/pool', admin.token)
  console.log(`[verify] pool returned ${pool.length} recent pool leads`)
  console.log('[done] realistic demo data seeding completed')
}

function dryRun() {
  const companyNames = new Set()
  const uscis = new Set()
  const phones = new Set()
  for (let i = 0; i < TOTAL_CUSTOMERS; i += 1) {
    companyNames.add(companyName(i))
    uscis.add(generateUsci(i + 1000))
  }
  for (let i = 0; i < TOTAL_LEADS; i += 1) {
    phones.add(phoneFor(i))
  }
  if (companyNames.size !== TOTAL_CUSTOMERS) {
    throw new Error(`customer names are not unique: ${companyNames.size}/${TOTAL_CUSTOMERS}`)
  }
  if (uscis.size !== TOTAL_CUSTOMERS) {
    throw new Error(`USCIs are not unique: ${uscis.size}/${TOTAL_CUSTOMERS}`)
  }
  if (phones.size !== TOTAL_LEADS) {
    throw new Error(`phones are not unique: ${phones.size}/${TOTAL_LEADS}`)
  }

  const stageCounts = new Map()
  const ownerCounts = new Map([['公海', 0]])
  for (const [, name] of salesProfiles) {
    ownerCounts.set(name, 0)
  }
  for (let i = 0; i < TOTAL_LEADS; i += 1) {
    const stage = stageFor(i)
    stageCounts.set(stage, (stageCounts.get(stage) ?? 0) + 1)
    const owner = i % 6 === 0 ? '公海' : salesProfiles[i % salesProfiles.length][1]
    ownerCounts.set(owner, (ownerCounts.get(owner) ?? 0) + 1)
  }

  console.log(`[dry-run] sales=${salesProfiles.length}`)
  console.log(`[dry-run] customers=${TOTAL_CUSTOMERS}, uniqueNames=${companyNames.size}, uniqueUsci=${uscis.size}`)
  console.log(`[dry-run] leads=${TOTAL_LEADS}, uniquePhones=${phones.size}`)
  console.log(`[dry-run] stages=${JSON.stringify(Object.fromEntries(stageCounts), null, 0)}`)
  console.log(`[dry-run] owners=${JSON.stringify(Object.fromEntries(ownerCounts), null, 0)}`)
  console.log(`[dry-run] sampleCustomer=${companyName(0)} / ${generateUsci(1000)}`)
  console.log(`[dry-run] sampleContact=${contactFor(3, 'BIM咨询').name}（${contactFor(3, 'BIM咨询').title}） / ${phoneFor(3)}`)
}

async function addProgressHistory(lead, owner, token, stage, offset) {
  const stages = stage === '未触达'
    ? ['未触达']
    : activeStages.slice(0, activeStages.indexOf(stage) + 1)
  const selected = stages.slice(Math.max(0, stages.length - 3))
  for (let i = 0; i < selected.length; i += 1) {
    const s = selected[i]
    const template = progressTemplates[s][(offset + i) % progressTemplates[s].length]
    await post(`/leads/${lead.id}/progress`, token, {
      method: trackMethods[(offset + i) % trackMethods.length],
      content: `${template} 跟进人：${owner.plainName}，客户联系人：${lead.seedContact.name}。`,
    })
  }
  return selected.length
}

async function login(email, password) {
  const data = await request('/auth/login', null, {
    method: 'POST',
    body: { email, password },
  })
  return data
}

async function get(path, token) {
  return request(path, token, { method: 'GET' })
}

async function post(path, token, body) {
  return request(path, token, { method: 'POST', body })
}

async function patch(path, token, body) {
  return request(path, token, { method: 'PATCH', body })
}

async function request(path, token, options) {
  const response = await fetch(`${API_BASE}${path}`, {
    method: options.method,
    headers: {
      'Content-Type': 'application/json; charset=utf-8',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
  })
  const text = await response.text()
  let envelope
  try {
    envelope = JSON.parse(text)
  } catch {
    throw new Error(`${options.method} ${path} returned non-JSON ${response.status}: ${text}`)
  }
  if (!response.ok || envelope.code !== 'SUCCESS') {
    throw new Error(`${options.method} ${path} failed ${response.status} ${envelope.code}: ${envelope.message}`)
  }
  return envelope.data
}

function requireEnv(name) {
  const value = process.env[name]
  if (!value) {
    throw new Error(`${name} is required for cleanup mode ${CLEANUP_MODE}`)
  }
  return value
}

function ownerFor(i, sales) {
  if (i % 6 === 0) {
    return null
  }
  return sales[i % sales.length]
}

function stageFor(i) {
  if (i % 10 === 0) return '已赢单'
  if (i % 10 === 1) return '已流失'
  return activeStages[i % activeStages.length]
}

function contactFor(i, businessType) {
  return {
    name: `${contactSurnames[i % contactSurnames.length]}${contactGivenNames[(i * 7) % contactGivenNames.length]}`,
    title: titlesByType[businessType][i % titlesByType[businessType].length],
  }
}

function phoneFor(i) {
  const prefixes = ['139', '138', '137', '136', '135', '158', '159', '188', '187', '186', '177', '176', '166']
  return `${prefixes[i % prefixes.length]}${String(21000000 + i * 3791).padStart(8, '0')}`.slice(0, 11)
}

function companyName(i) {
  const district = ['一部', '二部', '三部', '数字中心', '工程事业部', '创新中心'][Math.floor(i / companyPrefixes.length) % 6]
  return `${companyPrefixes[i % companyPrefixes.length]}${companyBodies[(i * 7) % companyBodies.length]}${district}${companySuffixes[(i * 3) % companySuffixes.length]}`
}

function contractAmount(type, i) {
  const base = type === 'BIM培训' ? 28000 : type === 'BIM咨询' ? 96000 : 180000
  const amount = base + (i % 17) * (type === '定制开发' ? 23000 : 8500)
  return `${amount}.00`
}

function signedDateFor(i) {
  const day = 1 + (i % 27)
  const month = 4 + (i % 2)
  return `2026-${String(month).padStart(2, '0')}-${String(day).padStart(2, '0')}`
}

function generateUsci(seed) {
  const chars = '0123456789ABCDEFGHJKLMNPQRTUWXY'
  const weights = [1, 3, 9, 27, 19, 26, 16, 17, 20, 29, 25, 13, 8, 24, 10, 30, 28]
  const body = ['9', '1']
  let n = seed * 7919 + 104729
  for (let i = 0; i < 15; i += 1) {
    n = (n * 1103515245 + 12345) >>> 0
    body.push(chars[n % chars.length])
  }
  const sum = body.reduce((acc, ch, idx) => acc + chars.indexOf(ch) * weights[idx], 0)
  const checkIndex = (31 - (sum % 31)) % 31
  return `${body.join('')}${chars[checkIndex]}`
}

main().catch((error) => {
  console.error(error.message)
  process.exit(1)
})
