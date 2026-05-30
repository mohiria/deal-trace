# Realistic Demo Data Seeding

This script rebuilds the online demo data with production-like Chinese sales, customer, lead, progress, and contract records.

It keeps existing Admin accounts and removes previous Sales/business data before seeding.

## Validate Locally

```bash
node scripts/seed-realistic-demo-data.mjs --dry-run
```

Expected scale:

- 10 Sales accounts
- 126 customers
- 126 leads
- 21 pool leads
- active, won, and lost lead stages

## Run On The Server

Create a database backup first.

```bash
mysqldump --default-character-set=utf8mb4 \
  -h "$DB_HOST" -P "${DB_PORT:-3306}" -u "$DB_USER" -p \
  dealtrace > "dealtrace-before-seed-$(date +%Y%m%d%H%M%S).sql"
```

Then run the seeder from the project root:

```bash
export DEALTRACE_API_BASE=http://114.132.164.71:8080/api
export DEALTRACE_ADMIN_EMAIL=admin@dealtrace.local
export DEALTRACE_ADMIN_PASSWORD='your-admin-password'
export DEALTRACE_SEED_CLEANUP_MODE=docker

export DB_HOST='your-mysql-host'
export DB_PORT=3306
export DB_USER='dealtrace'
export DB_PASSWORD='your-password'

node scripts/seed-realistic-demo-data.mjs
```

If the server has `mysql` but not Docker, use:

```bash
export DEALTRACE_SEED_CLEANUP_MODE=mysql
node scripts/seed-realistic-demo-data.mjs
```

If cleanup must be executed manually, generate SQL first:

```bash
node scripts/seed-realistic-demo-data.mjs
```

Then execute the generated `seed-cleanup.sql` with a UTF-8 connection and seed after cleanup:

```bash
mysql --default-character-set=utf8mb4 \
  -h "$DB_HOST" -P "${DB_PORT:-3306}" -u "$DB_USER" -p \
  dealtrace < seed-cleanup.sql

export DEALTRACE_SEED_CLEANUP_MODE=skip
node scripts/seed-realistic-demo-data.mjs
```

## Encoding Notes

- The script sends `Content-Type: application/json; charset=utf-8`.
- Cleanup SQL starts with `SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci`.
- Keep the script file encoded as UTF-8.

## Verify

Use Admin in the frontend:

```text
http://114.132.164.71:8081
```

Default seeded Sales password:

```text
Sales@2026!
```

Check for:

- Chinese names render correctly.
- Admin sees global lead/customer data.
- Sales users see their own leads.
- Won leads have contracts.
- Lost leads have loss reasons.
- Progress logs read like real sales follow-up notes.
