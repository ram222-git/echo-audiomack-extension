# Catalog Services

Date: 2026-11-24

## Architecture

A single signed transport (`AudiomackApiClient`) is wrapped by thin services. The extension class only wires Echo interfaces to these services.

| Component | Responsibility | Main endpoints |
|---|---|---|
| `AudiomackApiClient` | OAuth-signed HTTP, retries, JSON helpers | all |
| `AudiomackAuthManager` | Login/logout/session selection | `access_token`, `user` |
| `AudiomackSessionManager` | Persist token pair/expiry/profile | local Settings |
| `AudiomackSearchService` | Search tabs, all-results merge, quick search | `search`, `livesearch` |
| `AudiomackCatalogService` | Home shelves, trending/recent, categories | `music/trending`, `music/recent`, `playlist/categories` |
| `AudiomackArtistService` | Profile, uploads, albums, playlists, pinned, related | `artist/*`, `recommendations/*` |
| `AudiomackAlbumService` | Album metadata + ordered tracks | `music/album/{artist}/{slug}` |
| `AudiomackPlaylistService` | Playlist metadata + complete tracks | `playlist/{artist}/{slug}` |
| `AudiomackLibraryService` | Likes, saves, follows, history, playlist editing | `user/*`, `artist/*/favorites`, `playlist/*` |
| `AudiomackMetadataMapper` | JSON → Echo models | – |
| `AudiomackErrorHandler` | HTTP/network → actionable errors | – |
| `AudiomackStreamResolver` | Full-song signed URL resolution + validation | `music/play/{id}` |
| `AudiomackPlaybackService` | Echo `Streamable` → Media3 server source | – |

## Home feed

`loadHomeFeed()` exposes three tabs:

- **Discover**: Trending Now, New Releases, Featured Playlists, Genres & Collections.
- **Charts**: Top Songs, Fresh Uploads, Top Playlists.
- **Genres**: category grid; each category opens a feed of playlists.

Pagination is `PagedData.Continuous`; continuation tokens are page numbers. Empty sections are omitted rather than shown as fake successes.

Live result (2026-11-24):

```text
discover shelves=[discover_trending, discover_recent, discover_playlists, genres]
chart shelves=[chart_top_songs, chart_new_releases, chart_playlists]
```

## Search

`search` is called with `show=songs|albums|artists|playlists`. The search feed has five tabs: All, Songs, Albums, Artists, Playlists. “All” merges the four categories and de-duplicates by type + id.

Quick search combines `livesearch` query suggestions and media items. `deleteQuickSearch` is a no-op because Audiomack has no server-side search history; Echo owns local history.

Live result for “Karan Aujla”:

```text
songs=20, albums=20, artists=19, playlists=20
quick search items=22
```

## Albums

Album URLs are parsed into `(artistSlug, urlSlug)` and loaded through:

```http
GET music/album/{artistSlug}/{urlSlug}
```

Tracks receive the album title, cover, album id, order number and a streamable id. If the API omits `track_count`, the returned track count is used.

Live result: `AUJLA SZN 1` — 5 tracks, all with positive durations.

## Playlists

Playlist URLs are parsed the same way and loaded through:

```http
GET playlist/{artistSlug}/{urlSlug}
```

The endpoint returns the complete `tracks` array. Live result: `Verified: Punjabi` — 100 tracks, declared track count 100. No truncation or per-page stitching was needed.

Owned playlists can also be loaded by id through `playlist/{id}`.

## Artists

`resolveSlug` uses, in order:

1. `url_slug` / `artist_slug` extras,
2. a slug extracted from a public URL,
3. a numeric-id lookup through `artist?id=`,
4. a slugified name as a last resort.

The artist feed is built from `/uploads` (songs and albums), `/playlists`, `/pinned`, and optional related artists. Related artists require an account; when signed out that optional shelf is omitted and the rest of the page still loads.

Live result: `Karan Aujla` — shelves `artist_top_songs`, `artist_albums`, `artist_playlists`.

## Metadata mapping

The mapper reads real fields only:

- id, title, duration, explicit, genre, ISRC, release date, plays (`stats.plays-raw`), cover.
- uploader/artist/featuring for artist lists.
- `album_details` for album linkage.
- `premium_user_only`, `geo_restricted`, `stream_only`, `preview`, `status` for playability and extras.
- URL slugs for canonical detail requests.

Missing fields stay `null`. It does not invent durations, artists or stream URLs.

Artist splitting handles commas, `&`, `feat.`, `ft.`, `featuring`, `x`, `/`, `\`, `|`, `;` and “with”, plus featuring credits in the title.

## Caching

- Artist data: 5 minutes in memory.
- Album detail: 5 minutes.
- Playlist detail: 3 minutes, invalidated after edits.
- Favorite/following checks: 60 seconds.

Caches are per extension instance and are not persisted. This avoids duplicate requests during one screen while keeping data fresh.

## Removed scraping

The previous `AudiomackApi.kt` RSC/HTML parser has been deleted. Catalog, search, details, artist pages and playback no longer read the website. Only public URL strings are parsed for slugs; no HTML is fetched. See [SCRAPING_REPLACEMENT.md](SCRAPING_REPLACEMENT.md).
