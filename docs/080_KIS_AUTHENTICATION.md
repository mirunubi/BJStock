# KIS Authentication Foundation

Phase 3-A adds KIS Open API authentication only.

This phase does not call stock quotes, account APIs, or order APIs.

Runtime verification on a physical device is deferred until a device is available.

Status:

```text
BUILD / UNIT VERIFIED
RUNTIME DEFERRED — physical device pending
```

## KIS Environment

`KisEnvironment` has two values:

| Value | Meaning | Base URL |
| --- | --- | --- |
| `PRODUCTION` | Real-market Open API host | `https://openapi.koreainvestment.com:9443` |
| `VIRTUAL` | KIS paper Open API host | `https://openapivts.koreainvestment.com:29443` |

`PRODUCTION` is an authentication-server label only. It does not enable live trading.

Default selected environment: `PRODUCTION`.

App Key and App Secret are stored independently per environment.

URLs live in `KisEnvironmentConfig`. They are not copied through feature code.

## OAuth Flow

Token request:

```text
POST /oauth2/tokenP
```

Body:

```text
grant_type = client_credentials
appkey
appsecret
```

Connection Test in Settings:

```text
Credential present
      ↓
Cached token usable?
      ↓
If not, request token
      ↓
AUTHENTICATED
```

A successful token issue is the Phase 3-A connection success. No market-data API is called.

## Secret Storage

Plain App Key / App Secret are never stored in:

- Room
- `strings.xml`
- BuildConfig
- source
- `local.properties`
- Git
- Logcat
- crash messages
- analytics
- README / docs as real values

Storage shape:

```text
ciphertext
iv
version
```

Implementation: app-private `SharedPreferences` named `kis_secrets`, encrypted with `SecretCipher`.

External layers use `KisCredentialStore` and do not see encryption details.

## Android Keystore

Production cipher: `AesGcmSecretCipher`.

| Item | Value |
| --- | --- |
| Keystore | AndroidKeyStore |
| Alias | `bjstock_kis_secret_key_v1` |
| Algorithm | AES |
| Block mode | GCM |
| Padding | NoPadding |
| Key size | 256 bit |
| Purpose | ENCRYPT / DECRYPT only |

Deprecated APIs are not used:

- EncryptedSharedPreferences
- MasterKey
- MasterKeys
- androidx.security.crypto

Unit tests use `FakeSecretCipher` (in-memory AES/GCM). Passing those tests does not mean Android Keystore runtime is verified.

## Token Cache

Access tokens are secrets. They are encrypted in the same store, not in Room.

Reuse rule:

```text
expiresAt > now + safetyMargin
```

Safety margin: 5 minutes.

If a usable token exists, no network call is made.

`getValidToken()` is guarded by a Mutex so rotation, recomposition, navigation, and double taps cannot flood token issuance.

Decrypted App Secret is read only for the request and is not kept in an application singleton.

## Expiry

`expires_in` from the token response is converted to `expiresAtEpochMillis`.

If the field is missing, a 24-hour fallback is used.

## Authentication State

Domain states:

```text
NOT_CONFIGURED
READY
AUTHENTICATING
AUTHENTICATED
ERROR
```

The UI shows state only. Token values are never placed in UI state.

## Settings UI

Screen: KIS API Settings.

Fields:

- Environment
- App Key
- App Secret (password visual transformation)
- Credential status
- Authentication status
- Save
- Connection Test
- Delete credentials

After save, the screen does not redisplay the full secret. App Key uses a last-4 mask such as `****ABCD`. App Secret uses a placeholder.

Delete removes App Key, App Secret, access token, and expiry for that environment. The Keystore AES key itself is kept for possible later secrets.

## Security Rules

- No account number, HTS ID, account password, or product code collection in this phase
- No broker order APIs
- No HTTP body / Authorization / token logging, including debug builds
- Secret SharedPreferences file is excluded from Android Auto Backup and device transfer
- App reinstall or a new device requires the user to enter App Key / App Secret again
- Real KIS credentials are entered only on the device UI. They are not pasted into Cursor, Git, or docs

## Runtime Verification Pending

The following remain `DEFERRED — physical device pending`:

- Actual Android Keystore encrypt/decrypt on device
- Actual KIS token request against Open API
- Actual Settings UI runtime

Phase 2.1 device runtime remains in the same deferred bucket. Neither is a build/unit failure.
