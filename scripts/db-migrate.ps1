# BJStock Phase 1 migration runner.
# Applies db/migrations/*.sql in name order.
# Already-applied versions in bjstock.schema_migrations are skipped.

$ErrorActionPreference = "Stop"

$RepoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $RepoRoot

$Service = "bjstock-postgres"
$DbUser = "bjstock"
$DbName = "bjstock_dev"

function Invoke-Psql {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Sql
    )

    $Sql | docker compose exec -T $Service psql -U $DbUser -d $DbName -v ON_ERROR_STOP=1 -q
    if ($LASTEXITCODE -ne 0) {
        throw "psql failed with exit code $LASTEXITCODE"
    }
}

function Invoke-PsqlScalar {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Sql
    )

    $value = docker compose exec -T $Service psql -U $DbUser -d $DbName -tAc $Sql
    if ($LASTEXITCODE -ne 0) {
        throw "psql scalar query failed with exit code $LASTEXITCODE"
    }
    return ($value | Out-String).Trim()
}

function Invoke-PsqlFile {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Path
    )

    Get-Content -Raw -Encoding utf8 $Path |
        docker compose exec -T $Service psql -U $DbUser -d $DbName -v ON_ERROR_STOP=1 -f -
    if ($LASTEXITCODE -ne 0) {
        throw "Migration file failed: $Path"
    }
}

$status = docker compose ps --status running --services
if ($status -notcontains $Service) {
    throw "Docker service '$Service' is not running. Start it with: docker compose up -d"
}

Write-Host "Ensuring schema and schema_migrations..."

Invoke-Psql @"
CREATE SCHEMA IF NOT EXISTS bjstock;

CREATE TABLE IF NOT EXISTS bjstock.schema_migrations (
    version     TEXT PRIMARY KEY,
    filename    TEXT NOT NULL UNIQUE,
    applied_at  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
"@

$migrationDir = Join-Path $RepoRoot "db\migrations"
$files = @(Get-ChildItem -Path $migrationDir -Filter "*.sql" | Sort-Object Name)

if ($files.Count -eq 0) {
    Write-Host "No migration files found."
    exit 0
}

foreach ($file in $files) {
    if ($file.Name -notmatch '^(\d+)_') {
        throw "Migration filename must start with a numeric version: $($file.Name)"
    }

    $version = $Matches[1]
    $applied = Invoke-PsqlScalar "SELECT COUNT(*) FROM bjstock.schema_migrations WHERE version = '$version';"

    if ($applied -eq "1") {
        Write-Host "SKIP  $version  $($file.Name)"
        continue
    }

    Write-Host "APPLY $version  $($file.Name)"
    Invoke-PsqlFile -Path $file.FullName

    $filename = $file.Name.Replace("'", "''")
    Invoke-Psql "INSERT INTO bjstock.schema_migrations (version, filename) VALUES ('$version', '$filename');"
    Write-Host "OK    $version  $($file.Name)"
}

Write-Host ""
Write-Host "Applied migrations:"
Invoke-Psql "SELECT version, filename, applied_at FROM bjstock.schema_migrations ORDER BY version;"
