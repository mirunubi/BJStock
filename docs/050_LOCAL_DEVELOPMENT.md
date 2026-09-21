# BJStock Local Development

## What Phase 0 Provides

Phase 0 provides a local Git repository, foundation documents, and a Docker PostgreSQL laboratory.

It does not provide an Android project, KIS API client, or business tables.

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

Phase 0 creates schema `bjstock` only. Business tables are not created here.
