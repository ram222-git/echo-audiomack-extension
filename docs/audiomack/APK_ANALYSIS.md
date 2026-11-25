# Audiomack APK Analysis

Date: 2026-11-24

## Artifact

| Property | Value |
|---|---|
| File | `apk/com.audiomack_8.11.4-33762_minAPI24(arm64-v8a,armeabi-v7a,x86,x86_64)(nodpi)_apkmirror.com.apk` |
| Package | `com.audiomack` |
| Version name | `8.11.4` |
| Version code | `33762` |
| minSdk / targetSdk | 24 / 36 |
| ABIs | arm64-v8a, armeabi-v7a, x86, x86_64 |
| DPI | nodpi |

The manifest was decoded with `aapt2 dump badging`. The DEX was decompiled with JADX 1.5.1 into a temporary directory (`/tmp/amjadx`); no APK files were added to this repository.

## What the official app uses

The app is not a website wrapper for API traffic. Its networking layer uses an OAuth 1.0a signed REST API:

- Base URL: `https://api.audiomack.com/v1/`
- Signature method: `HMAC-SHA1`
- Consumer key: `audiomack-android`
- Consumer secret: `051ecef67795633034e15cf94cd54872`

These are **public application credentials embedded in the official Android client**, not user credentials. The extension uses them as defaults and allows them to be overridden in settings. They are not secrets belonging to this project or to any user.

### Relevant decompiled components

| Obfuscated class | Role |
|---|---|
| `defpackage/ldc.java` | Signpost-style OAuth interceptor; installs the consumer key/secret on every request |
| `defpackage/r81.java` | Builds the `Authorization: OAuth ...` header |
| `defpackage/in3.java` | Credentials model; stores token, token secret, expiration, user id, URL slug |
| `AuthResponse` response model | Parses `oauth_token`, `oauth_token_secret`, `oauth_token_expiration`, `user{...}` |

The response model exposes exactly these session fields:

```text
oauth_token
oauth_token_secret
oauth_token_expiration
user.id
user.screen_name
user.url_slug
social_registration
```

There is no refresh-token endpoint in the app. The token pair plus expiration is the session. An expired token requires a new password exchange.

### API service classes found in the APK

`ApiAuth`, `ApiPlay`, `ApiMusicInfo`, `ApiArtists`, `ApiPlaylists`, `ApiPlaylistsEditing`, `ApiFavorites`, `ApiFollow`, `ApiSearch`, `ApiTrending`, `ApiRecentlyAdded`, `ApiRecentlyPlayed`, `ApiUser`, `ApiDownloads`, `ApiFeed`, `ApiRecommendations`, `ApiPlaylistCategories`.

These map to the endpoints catalogued in [API_INVENTORY.md](API_INVENTORY.md).

## Playback discovery

The important playback path is:

```http
GET /v1/music/play/{musicId}?hq=1
```

Response shape:

```json
{
  "signedUrl": "https://music.audiomack.com/hq/...m4a?...",
  "playUuid": "..."
}
```

The URL is a time-limited CloudFront-signed object on `music.audiomack.com`, served as `audio/mp4` with `Accept-Ranges: bytes`. The official app does not synthesize media URLs; it asks the API for this signed URL.

The extension therefore delegates audio playback to Echo/Media3 and only resolves the signed URL at playback time. See [PLAYBACK_ARCHITECTURE.md](PLAYBACK_ARCHITECTURE.md).

## Authentication discovery

The official login exchange is a signed form POST:

```http
POST /v1/access_token
Content-Type: application/x-www-form-urlencoded

x_auth_mode=client_auth
x_auth_username=<email>
x_auth_password=<password>
```

All form fields are included in the OAuth signature base string. A live probe with an unknown email returned:

```text
HTTP 422
{"errors":["Login validation failed: That email address was not found."]}
```

That confirms the endpoint, signature scheme and error shape without needing a real account. A successful login could not be performed because no authorized test account is available; this is recorded as **BLOCKED**, not guessed.

## Access-control boundaries

The analysis did not attempt to bypass authentication, subscription checks, DRM or geo-restrictions.

- Premium-only tracks are identified by `premium_user_only=yes`.
- Region locks use `geo_restricted` / `geo_lock` fields.
- The extension does not fabricate entitlement, does not strip these flags, and does not resolve streams for tracks it marks unplayable.
- Account-only endpoints return `401` when called anonymously; this is surfaced to the host as `LoginRequired` (or an `Unauthorized` error for a rejected stored session).

## Limitations of this analysis

- The APK is obfuscated; class names above are from the decompiled 8.11.4 build and may change in later versions.
- The API is not a documented public contract. It can change without notice.
- Decompilation was used only to identify the official client’s protocol and endpoint names. No proprietary code or assets were copied into the extension.
