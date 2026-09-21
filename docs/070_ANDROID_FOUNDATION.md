# BJStock Android Foundation

Phase 2 creates a single-module Jetpack Compose app with Room as the on-device runtime database.

The APK does not connect to Docker PostgreSQL.

## Application identity

- Application name: BJStock
- Application ID / namespace: `com.mirunubi.bjstock`

## SDK

- minSdk: 28
- targetSdk: 36
- compileSdk: 37 (required by Compose BOM 2026.09.00 and Hilt Navigation 1.4.0; targetSdk stays 36)
- JDK: 17

## Toolchain

Versions are centralized in `gradle/libs.versions.toml`.

- Android Gradle Plugin: 9.4.0
- Gradle: 9.6.0
- Kotlin: 2.4.10
- KSP: 2.3.12
- Compose BOM: 2026.09.00
- Room: 2.8.5
- Hilt: 2.60.1

KSP 2.3.12 is the stable KSP2 line that supports AGP 9 and Kotlin 2.4. Alpha/Beta/RC artifacts are not used.

## Package structure

```text
app/src/main/java/com/mirunubi/bjstock/
  BJStockApplication.kt
  MainActivity.kt
  core/database/
    entity/
    dao/
    converter/
    mapping/
    BJStockDatabase.kt
  core/di/
  core/model/
  feature/dashboard/
  ui/theme/

KIS authentication packages added in Phase 3-A are documented in `docs/080_KIS_AUTHENTICATION.md`.
```

Single module `:app`. No extra Gradle modules in this phase.

## Room

- Filename: `bjstock.db`
- Version: 1
- Entities: 16 business tables
- `schema_migrations` is not a Room table
- `exportSchema = true` → `app/schemas/`
- Enum codes stored as String, never ordinal
- Money stored as Long won, not Double

## Permissions

- `INTERNET` is declared for a later KIS phase
- No Location, Contacts, SMS, Phone, Storage, Camera, or Microphone

## Signing

Debug APK only. No release keystore in this phase. Signing keys must never be committed.

## APK build

From the repository root, with JDK 17 and Android SDK configured:

```powershell
.\gradlew.bat clean
.\gradlew.bat assembleDebug
.\gradlew.bat testDebugUnitTest
```

Output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

`local.properties` (`sdk.dir`) is gitignored.

Docker Compose is not required to build or run the Android app.
