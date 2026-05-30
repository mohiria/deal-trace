# Docker Deployment

DealTrace can run as two Docker images:

- `dealtrace-backend:local`: Spring Boot API service on port `8080`, context path `/api`.
- `dealtrace-frontend:local`: Nginx static frontend on port `80`, reverse-proxying `/api` to backend.

MySQL is external. The application always uses database name `dealtrace`; configure host, port, user, and password through environment variables.

## Prerequisites

- Docker with Docker Compose.
- An external MySQL 8.4 instance with database `dealtrace`.
- The DB user must be able to run Flyway migrations.

## Configure

Copy the example file and edit values:

```bash
cp .env.example .env
vim .env
```

Required variables:

- `DB_HOST`
- `DB_PORT`
- `DB_USER`
- `DB_PASSWORD`
- `DEALTRACE_JWT_SECRET`
- `DEALTRACE_ADMIN_EMAIL`
- `DEALTRACE_ADMIN_PASSWORD`

`DEALTRACE_ADMIN_EMAIL` and `DEALTRACE_ADMIN_PASSWORD` are used only when the `account` table has no `ADMIN` account. Existing Admin accounts are not overwritten.

Do not set `DB_HOST=127.0.0.1` unless MySQL is inside the same backend container. From Docker, `127.0.0.1` means the container itself. Use the MySQL server IP, DNS name, or a Docker-reachable host name.

## Build And Run

```bash
docker compose build
docker compose up -d
```

Open the app:

```text
http://localhost:8081
```

Backend health check:

```text
http://localhost:8080/api/health
```

Expected response:

```json
{"code":"SUCCESS","message":"OK","data":{"status":"UP"}}
```

## Logs And Stop

```bash
docker compose logs -f backend
docker compose logs -f frontend
docker compose down
```

## Notes

- The frontend container serves Vue Router history routes through `try_files ... /index.html`.
- The frontend container proxies `/api/` to `http://backend:8080/api/` inside the Compose network.
- To expose different host ports, set `BACKEND_PORT` and `FRONTEND_PORT` in `.env`.
