# Echo : Audiomack Extension

An [Echo](https://github.com/brahmkshatriya/echo) music extension that talks to Audiomack's
official Android API (`https://api.audiomack.com/v1/`) instead of scraping the website. It adds
browsing, search, accounts, library management and full-song playback to Echo.

The extension is not affiliated with Audiomack. It only plays tracks that Audiomack makes
available to the current session; it does not bypass authentication, subscriptions, DRM, geo
restrictions or other access controls.

## Features

- **Home and discovery** — trending songs, recent uploads, genre charts and featured playlists.
- **Search** — songs, albums, artists and playlists, plus Echo quick-search suggestions.
- **Songs** — full metadata: artists, album, duration, plays, release date, genre, explicit
  flag, ISRC, cover art and playability state.
- **Albums** — complete ordered tracklists; album-shaped rows are resolved to their real song
  before playback instead of being queued as albums.
- **Artists** — profile, top songs, albums, playlists, pinned items and related artists.
- **Playlists** — full tracklists, create/delete, edit title/description/privacy, add and remove
  tracks. Audiomack has no reorder or insert-at-index endpoint, so those actions are reported as
  unsupported rather than faked.
- **Account library** — likes, saved albums/playlists, followed artists and listening history
  when signed in.
- **Playback** — authorized HQ stream resolution (`music/play/{id}?hq=1`) with full-length,
  seekable media handed to Echo/Media3 for playback or download.
- **Authentication** — OAuth 1.0a login through Echo. Only the issued token/secret and public
  profile are stored; the password is never saved.

## Requirements

- Android with a recent Echo build.
- JDK 17 and the Android SDK for building.
- A network connection to Audiomack.

## Build

```bash
# Extension bundle (JAR used by Echo)
./gradlew :ext:shadowJar

# Installable debug extension APK
./gradlew :app:assembleDebug
```

The debug APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

The extension identifier is `dev.brahmkshatriya.echo.extension.audiomack` and the application
label is `Echo : Audiomack Extension`.

### Install on a device

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

If an older build with the same version metadata is already installed, uninstall it first so Echo
reloads the new extension:

```bash
adb uninstall dev.brahmkshatriya.echo.extension.audiomack
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Configuration

Extension metadata lives in [`gradle.properties`](gradle.properties):

| Property | Purpose |
|---|---|
| `extType` | Echo extension type (`music`). |
| `extId` | Extension id. |
| `extClass` | Entry class (`AudiomackExtension`). |
| `extName` | Display name. |
| `extDescription` | Description shown by Echo. |
| `extAuthor` / `extAuthorUrl` | Author metadata. |
| `extRepoUrl` / `extUpdateUrl` | Repository and update source. |

## Authentication

Echo starts the login flow through the extension's auth manager. The extension exchanges the
credentials with Audiomack's `access_token` endpoint and stores:

- OAuth token and token secret
- expiry time
- user id, URL slug and display name

Passwords are used only for that exchange and are never written to settings. Logging out clears
the stored token. Echo temporarily deactivating a user does **not** delete the stored session, so
the next launch can restore it.

## Playback and artist navigation

- Playback resolves a signed media URL per track. A `HEAD` request is used in tests to confirm
  the response is real audio (`audio/*`) and full length rather than a preview.
- Album objects are never playable. When a feed exposes an album id, the extension finds the
  matching song inside the release and plays that song id.
- Audiomack track payloads include a numeric id only for the uploader. Featured artists arrive
  as names. The extension:
  1. uses the uploader's numeric id directly (`artist?id={id}`),
  2. resolves a featured artist's name through the artist search endpoint and uses the returned
     numeric id,
  3. verifies a candidate slug against the artist's name before falling back to it.

  This prevents the second artist on a collaboration from opening a different artist whose slug
  happens to match a guessed name.

## Project structure

```text
app/                                   Debug extension APK host
ext/src/main/java/.../
  AudiomackExtension.kt                Echo client entry point and feeds
  AudiomackApiClient.kt                OAuth-signed HTTP transport and endpoints
  AudiomackOAuth.kt                    OAuth 1.0a signing and app consumer
  AudiomackSessionManager.kt           Session persistence
  AudiomackAuthManager.kt              Login/logout integration
  AudiomackMetadataMapper.kt           API JSON -> Echo models
  AudiomackCatalogService.kt           Home, charts and discovery
  AudiomackSearchService.kt            Search feeds
  AudiomackAlbumService.kt             Album detail and tracklists
  AudiomackArtistService.kt            Artist profiles, shelves and id resolution
  AudiomackPlaylistService.kt          Playlist detail
  AudiomackLibraryService.kt           Likes, saves, follows, history, mutations
  AudiomackPlaybackService.kt          Streamable media
  AudiomackStreamResolver.kt           Signed URL validation
  AudiomackErrorHandler.kt             HTTP/network error mapping
docs/audiomack/                        API and behaviour notes
```

## Testing

Offline unit tests are always safe:

```bash
./gradlew :ext:test
```

Live catalog tests require network access and opt in explicitly:

```bash
AUDIOMACK_LIVE_TESTS=1 ./gradlew :ext:test
```

Authenticated tests also read an already-issued OAuth token from environment variables loaded
from a local, git-ignored `.env` file:

```bash
set -a; . ./.env; set +a
AUDIOMACK_LIVE_TESTS=1 AUDIOMACK_ACCOUNT_TESTS=1 ./gradlew :ext:test
```

Expected variables: `AUDIOMACK_OAUTH_TOKEN`, `AUDIOMACK_OAUTH_TOKEN_SECRET`,
`AUDIOMACK_USER_ID`, `AUDIOMACK_USER_SLUG` (and optionally `AUDIOMACK_USER_NAME`). Never commit
`.env`; it is ignored by [`.gitignore`](.gitignore).

The suite covers metadata mapping, artist splitting and id resolution, session persistence,
error messages, catalog/search/album/playlist/artist feeds, stream resolution, and account
mutations (reverted after each test).

## Known limitations

- Premium-only, private, geo-restricted or otherwise unauthorized tracks cannot be played; they
  are marked with the reason instead of being faked.
- Audiomack exposes no playlist reorder or insert-at-index endpoint.
- Artist favourites are not exposed by the API; following is supported.
- Related tracks and related artists are account-only and are omitted when signed out.
- A featured artist name with multiple identical Audiomack profiles is matched to the active
  profile with the most followers; the real id is used from there.
- Live account tests need a valid local token. Password-login success cannot be tested without
  the account owner's password.
