# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repository state

This repo is **pre-implementation**. There is no `backend/` or `frontend/` yet — only:

- `docs/产品需求文档 (PRD).md` — PRD v3.3 (Chinese, authoritative)
- `docs/技术架构与工程约束.md` — technical architecture and engineering constraints (Chinese, authoritative)
- `prototype/dealtrace-workbench.html` — single-file static UI mockup (not the real frontend)
- `openspec/` — OpenSpec scaffolding (`specs/` empty, `changes/archive/` empty, `config.yaml`)
- `.claude/commands/opsx/` and `.claude/skills/openspec-*/` — local slash commands and skills for the OpenSpec workflow

When asked to "build the app" or "start the backend/frontend", the directories don't exist yet — they need to be created per §3 of the tech-arch doc. Don't pretend code exists.

## Source-of-truth hierarchy

When the two design docs, an OpenSpec spec, or a test disagree, the order of authority is:

1. `docs/产品需求文档 (PRD).md`
2. `docs/技术架构与工程约束.md`/
3. OpenSpec `specs/` (archived stable behavior)
4. OpenSpec `changes/<change>/` (in-flight delta)
5. QA artifacts under `openspec/changes/<change>/qa/`

If OpenSpec output or QA tests contradict (1) or (2), stop and trigger a Requirement Conflict Gate — do **not** silently relax assertions or rewrite expected behavior. The tech-arch doc says this explicitly in §13.6.

The product is **商迹 DealTrace** (project code `deal-trace`), a lightweight B2B CRM. Most docs and likely future specs are in Chinese; keep new specs/docs in Chinese unless asked otherwise.

## Planned stack (per `docs/技术架构与工程约束.md`)

Don't substitute alternatives without checking — these choices are deliberate:

- **Frontend**: Vue 3 + TypeScript + Vite + **Arco Design Vue** + Vue Router + Pinia + Axios. **Do not introduce Tailwind CSS** — it conflicts with Arco's theme system (explicitly called out in §10).
- **Backend**: Java 24 + Spring Boot 4.x + Spring Security + MyBatis Plus + BCrypt + JWT, RESTful JSON, OpenAPI 3 contracts.
- **Database**: MySQL 8.4 LTS (InnoDB) + Flyway migrations. Money fields use exact numeric types, never floats.
- **Testing**: Backend integration tests via **Testcontainers** against real MySQL 8.4 (not H2, not mocks for migration-sensitive paths).

Recommended monorepo layout: `frontend/`, `backend/`, `docs/`, `openspec/{specs,changes,explorations}/`.

## Workflow: OpenSpec (spec) → vibe-coding-qa (TDD implementation)

This is a **hard constraint**, not a suggestion. All non-trivial work goes through two sequential phases. Free-form "just write the code" is not allowed for behavior-changing work.

### Phase 1 — OpenSpec: turn PRD into behavior specs

PRD §-by-§ requirements are split into per-domain `spec.md` files via the OpenSpec change workflow. Local slash commands:

- `/opsx:explore` — investigation mode before proposing (use when scope or approach is unclear)
- `/opsx:propose` — create a new change directory with `proposal.md`, `design.md`, `tasks.md`, and per-domain `specs/<capability>/spec.md`
- `/opsx:apply` — drive implementation of tasks from a change
- `/opsx:archive` — finalize a completed change, promoting delta specs into `openspec/specs/`

The first change is expected to be `openspec/changes/bootstrap-dealtrace-mvp/` with sub-specs split by domain: `auth-account`, `customer`, `lead`, `permission`, `progress-log`, `contract`, `dashboard`, `frontend-workbench` (see tech-arch §13.2).

`spec.md` files contain **behavior contracts only** — Requirement / Scenario / Given-When-Then. They must **not** contain DB schemas, class names, file paths, or step-by-step implementation. Those belong in `design.md` and `tasks.md`. If you find yourself writing Java/Vue identifiers into a spec, you're in the wrong file.

### Phase 2 — vibe-coding-qa: TDD-driven implementation

Once a change has tasks, the `vibe-coding-qa` skill (see `.claude/skills/vibe-coding-qa/`) governs how those tasks turn into code. Read `.claude/skills/vibe-coding-qa/SKILL.md` and `references/qa-constitution.md` before implementing.

**TDD gate — before touching production code, one of these must exist:**

1. A lightweight test design for the behavior being changed (`templates/lightweight-test-design.md`).
2. Valid **Red evidence** — a test that fails *for the expected-behavior reason*. Syntax errors, missing imports, fixture failures, or env breakage are blockers, **not** Red evidence.
3. A reusable existing failing test that already proves the behavior gap.
4. A documented non-TDD exception with alternative validation and remaining risk.
5. An exact, written blocker that prevents creating the Red test (then escalate; don't skip).

**Layered testing — prefer the lowest effective layer:**

- Unit tests first (`backend/src/test/.../UnitTest.java`, `frontend/src/**/*.test.ts`) for business rules, USCI/phone validators, dedupe predicates, masking helpers.
- API/integration tests for service contracts, authorization, transactional behavior, DB constraints. Backend uses **Testcontainers + MySQL 8.4 LTS**, not H2, not mocks (per tech-arch §12).
- E2E (`frontend/tests/e2e/...`) only for critical user journeys: login, customer creation, lead creation, public-pool claim, win/loss closure. E2E uses scenario-first design, not strict Red-Green.

**Anti-fake-test rules (non-negotiable):**

- Never weaken assertions, delete negative cases, skip tests, or change expected behavior just to make a suite pass.
- Never modify or delete an existing test without stating the requirement authority and whether the new requirement extends, amends, supersedes, or conflicts with the prior baseline.
- Runtime smoke validation is not coverage — it doesn't replace unit/API/E2E tests.

### Requirement Conflict Gate

If QA test design, an existing test, current implementation, an old spec, an API contract, or a data model contradicts PRD v3.3 or `docs/技术架构与工程约束.md`, **stop**. Don't silently relax assertions, don't rewrite expected behavior, don't "just match what the code does." Surface the conflict, cite both sources, and ask the user to resolve. This rule is restated in tech-arch §13.6 and `vibe-coding-qa/CLAUDE.md` — treat them as one gate.

### Artifacts and locations

- QA design/reporting artifacts live under `openspec/changes/<change>/qa/`: `lightweight-test-design.md`, `regression-impact-analysis.md`, `qa-test-report.md`. Use the templates in `.claude/skills/vibe-coding-qa/templates/` as the structural starting point.
- Automated test **code** lives in real engineering paths: `backend/src/test/...`, `frontend/src/**/*.test.ts`, `frontend/tests/e2e/...`. Never put runnable tests in `qa/`.
- When practical, validate QA artifact structure with `node .claude/skills/vibe-coding-qa/scripts/qa_artifacts.mjs check <template-name> <artifact-path>` and resolve any FAIL/WARN before merging.
- Final QA reports must cite evidence: command output, response bodies, logs, screenshots, CI output — not prose claims.

## Non-obvious domain rules (read before implementing)

These rules are easy to violate. Most are enforced server-side with DB constraints + transactions; the frontend is UX-only and never a trust boundary.

**Customer uniqueness** (PRD §7.2, §8.1)
- 统一社会信用代码 (USCI) is globally unique. Before save/dedupe: trim, uppercase letters, then validate 18-char standard format including the checksum digit.
- Customer name is also unique after trimming. No 别名 / 简称 / fuzzy-merge in MVP.
- Customers carry **no** contact info, no contact phone, no source, no owning salesperson. All of those live on leads.

**Lead duplication** (PRD §7.3, §8.2)
- A "duplicate" is `(natural year, customer, business type)`. Natural year is derived from creation timestamp; users cannot set it.
- Blocked if an in-progress duplicate exists (未触达 / 初步沟通 / 方案报价 / 商务谈判).
- Blocked if a 已赢单 duplicate exists.
- **Allowed** if only 已流失 duplicates exist — but the UI must surface the historical loss reason/time.
- Same customer, different business type → not a duplicate. Different year → not a duplicate.
- Contact person is metadata only, not part of the dedupe key.

**Public pool & claiming** (PRD §7.5)
- Claim is a transactional server operation. Only one Sales wins; the rest get `LEAD_ALREADY_CLAIMED`.
- Sales see masked phone numbers in the public pool; only after a successful claim does the phone become plaintext to them.
- Disabled Sales accounts cannot claim, cannot be assigned, cannot be transferred to.

**Ended state is read-only** (PRD §7.7, §8.5)
- 已赢单 and 已流失 leads cannot have new progress logs, stage changes, reclaim-to-pool, reassignment, or re-marking. MVP has no undo for either.
- Each lead produces **at most one** contract record (DB-enforced).

**Progress logs and system logs are append-only**
- Progress logs are user-created, never editable/deletable — not even by Admin. Corrections happen by appending another log.
- System logs are system-generated, also never editable. Both display newest-first.
- Timestamps on both are server-generated; never accept client clocks.

**Phone masking** (tech-arch §9.4)
- 11-digit mobile → `138****5678` (3+4).
- Other lengths ≥ 8 → keep first 3 and last 4, middle becomes `****`.
- Lengths < 8 → show only last 2.

**Dashboard ownership semantics** (PRD §7.12)
- Stock metrics (e.g. today's new leads for a Sales) use **current** ownership.
- Event metrics (本月赢单金额, 本月流失率) use **ownership-at-event-time**, not current.
- 本月流失率 with denominator 0 displays `--`, not `0%`.

**Auth & accounts** (PRD §7.1)
- No self-signup. The initial Admin is provisioned by deployment config (email + initial password). Admin creates Sales accounts only; MVP does not support creating additional Admins or self-service password reset.
- Disabling a Sales does **not** auto-reassign their leads — Admin must manually recycle or transfer.

## Things explicitly **out of scope** for MVP

Don't propose these without explicit user approval (PRD §10):
multi-tenant, multi-contact, CSV import, email notifications, automatic SLA recycling, contract approval, payment/invoice, customizable fields, business-registry API integration, customer alias/merge, win/loss undo, contract amount/date edits, overseas phone formats, customer records without USCI.

## Commands

There is no build/test/lint toolchain yet — `backend/` and `frontend/` don't exist. Once they do, commands will live in their respective package metadata (Maven/Gradle for backend, `package.json` for frontend). Until then, the only "commands" are the OpenSpec slash commands listed above.
