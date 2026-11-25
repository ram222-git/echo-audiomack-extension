# Audiomack API Inventory

Date: 2026-11-24

Base URL: `https://api.audiomack.com/v1/`

Every request is OAuth 1.0a signed. Anonymous catalog requests use only the public app consumer; account requests also include the user token pair. Responses are JSON and are usually wrapped in a `results` field.

## Authentication

| Method | Path | Auth | Purpose | Verified |
|---|---|---|---|---|
| POST | `access_token` | app consumer + signed form | Password exchange (`x_auth_mode=client_auth`) | Yes: unknown email returns HTTP 422 |
| DELETE | `access_token` | user token | Revoke current token | No: requires a token |
| GET | `user` | user token | Current user profile | No: requires a token |

## Catalog and discovery

| Method | Path | Parameters | Purpose | Verified |
|---|---|---|---|---|
| GET | `music/trending` | `page` | Trending songs | Yes, HTTP 200 |
| GET | `music/recent` | `page` | Recently added songs | Yes, HTTP 200 |
| GET | `music/{genre}/trending` | `page` | Genre trending | Implemented, not exercised live |
| GET | `playlist/categories` | `featured=yes`, `limit` | Featured playlist categories | Yes, HTTP 200 |
| GET | `playlist/categories` | `slug`, `page` | Playlists in a category | Yes, used by home/featured feed |
| GET | `search` | `q`, `show`, `page` | Search: `songs`, `albums`, `artists`, `playlists` | Yes, HTTP 200 |
| GET | `livesearch` | `q`, `limit` | Type-ahead suggestions | Yes, HTTP 200 |

## Track, album, playlist, artist details

| Method | Path | Purpose | Verified |
|---|---|---|---|
| GET | `music/{id}` | Track detail | Yes, HTTP 200 |
| GET | `music/song/{artistSlug}/{urlSlug}` | Track detail by public URL parts | Implemented fallback |
| GET | `music/album/{artistSlug}/{urlSlug}` | Album + ordered `tracks` | Yes, HTTP 200 |
| GET | `playlist/{artistSlug}/{urlSlug}` | Playlist + complete `tracks` | Yes, HTTP 200; 100/100 tracks |
| GET | `playlist/{id}` | Playlist detail by id | Implemented fallback for owned playlists |
| GET | `artist/{slug}` | Artist profile | Yes, HTTP 200 |
| GET | `artist?id={numericId}` | Artist profile by numeric id | Implemented fallback |

## Artist content

| Method | Path | Purpose | Verified |
|---|---|---|---|
| GET | `artist/{slug}/uploads` | Songs and albums, paged | Yes, HTTP 200 |
| GET | `artist/{slug}/playlists` | Artist playlists | Yes, HTTP 200 |
| GET | `artist/{slug}/pinned` | Pinned featured items | Yes, HTTP 200 (array shape varies) |
| GET | `artist/{slug}/favorites` | Liked songs/albums/playlists, `show` required | No: account only |
| GET | `artist/{slug}/following` | Followed artists | No: account only |

## Recommendations

| Method | Path | Purpose | Verified |
|---|---|---|---|
| GET | `recommendations/related-songs` | Related tracks | Returns 401 `Invalid signature` anonymously; implemented as optional |
| GET | `recommendations/related-artists/{artistId}` | Related artists | Returns 401 anonymously; implemented as optional |

The 401 on recommendations is treated as “account-only section unavailable while signed out”, not as a signature bug: every other signed endpoint (including `music/play` and `access_token`) works with the same signer.

## Library

| Method | Path | Purpose | Verified |
|---|---|---|---|
| GET | `user/playlists` | Current user’s playlists | No: HTTP 401 anonymously |
| GET | `user/listen-history` | Recently played | No: account only |
| PUT / DELETE | `music/{id}/favorite` | Like/unlike a song or album | No: account only |
| PUT / DELETE | `playlist/{id}/favorite` | Save/remove a playlist | No: account only |
| PUT / DELETE | `artist/{slug}/follow` | Follow/unfollow | No: account only |

## Playlist editing

| Method | Path | Purpose | Verified |
|---|---|---|---|
| POST | `playlist` | Create playlist | No: account only |
| PUT | `playlist/{id}` | Edit title/description/privacy | No: account only |
| DELETE | `playlist/{id}` | Delete playlist | No: account only |
| POST | `playlist/{id}/track` | Append one track | No: account only |
| DELETE | `playlist/{id}/track/{ids}` | Remove one or more tracks | No: account only |

Audiomack exposes no documented endpoint for inserting at an arbitrary index or reordering tracks. The extension reports those operations as `NotSupported` instead of pretending they succeeded.

## Playback

| Method | Path | Purpose | Verified |
|---|---|---|---|
| GET | `music/play/{id}` | Resolve authorized stream; `hq=1` requests HQ | Yes, HTTP 200 with `signedUrl` + `playUuid` |

Observed stream headers:

```text
HTTP 200
Content-Type: audio/mp4
Content-Length: 5182364
Accept-Ranges: bytes
```

A range request for bytes `0-1023` returns HTTP 206 and 1024 bytes.

## Response and error shapes

Common success envelope:

```json
{ "results": [ ... ] }
```

Common error:

```json
{ "errorcode": "1004", "message": "Request unauthorised" }
```

Login validation error:

```json
{ "errors": ["Login validation failed: That email address was not found."] }
```

The client maps `401` to login/unauthorized flows, `403`/`404`/`422` to descriptive failures, `429` to rate-limit handling with `Retry-After`, and `5xx` to a temporary-service error.
