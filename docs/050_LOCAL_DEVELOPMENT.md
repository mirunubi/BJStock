# BJStock Local Development

## What Local Development Provides

The repository contains foundation documents, a Docker PostgreSQL laboratory, and the Phase 1 business schema.

It does not provide an Android project, KIS API client, or strategy execution code.

## Prerequisites

- Git
- Docker Desktop / Docker Engine with Compose
- PowerShell

## Repository Layout

```text
BJStock/
├── docs/
├── db/
│   ├── init/
│   ├── migrations/
│   └── scripts/
├── scripts/
├── .env.example
├── .gitignore
├── docker-compose.yml
└── README.md
```

## Environment File

Copy `.env.example` to `.env` and set a local password.

`.env` is gitignored. Do not commit secrets.

```text
POSTGRES_DB=bjstock_dev
POSTGRES_USER=bjstock
POSTGRES_PASSWORD=CHANGE_ME
POSTGRES_PORT=55432
```

## Docker PostgreSQL Laboratory

This PostgreSQL instance is for schema design and SQL experiments.

It is not the Android runtime database. The future APK uses Room / SQLite and must not connect to this container.

### Start

```powershell
docker compose up -d
docker compose ps
```

### Connection

- Host: `localhost`
- Port: `55432`
- Database: `bjstock_dev`
- User: `bjstock`
- Schema: `bjstock`

### Health

The `bjstock-postgres` service uses `pg_isready`.

### Stop

```powershell
docker compose down
```

Data persists in the named volume `bjstock_postgres_data` unless the volume is removed.

## Init SQL

`db/init` runs only on first volume initialization.

It creates schema `bjstock` only. Business tables are not defined there.

## Migrations

Apply pending files from `db/migrations` in name order:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\db-migrate.ps1
```

The runner records versions in `bjstock.schema_migrations` and skips already applied files.

Verification:

```powershell
Get-Content -Raw .\db\scripts\verify_phase1.sql | docker compose exec -T bjstock-postgres psql -U bjstock -d bjstock_dev -f -
Get-Content -Raw .\db\scripts\verify_strategy_weights.sql | docker compose exec -T bjstock-postgres psql -U bjstock -d bjstock_dev -f -
```
