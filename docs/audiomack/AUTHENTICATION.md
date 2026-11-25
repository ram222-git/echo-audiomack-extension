# Authentication

Date: 2026-11-24

## Scheme

Audiomack uses OAuth 1.0a with HMAC-SHA1, as implemented by the official Android app.

- Consumer key: `audiomack-android`
- Consumer secret: `051ecef67795633034e15cf94cd54872`
- API base: `https://api.audiomack.com/v1/`

These consumer values are the public application credentials from the official client. They identify the app, not the user. They are defaults only and can be replaced in extension settings.

## Login exchange

```http
POST https://api.audiomack.com/v1/access_token
Authorization: OAuth ...
Content-Type: application/x-www-form-urlencoded

x_auth_mode=client_auth
x_auth_username=<email>
x_auth_password=<password>
```

Important detail: the three form fields are part of the OAuth signature parameter set. Signing only the URL or only the OAuth header produces an invalid signature.

Response fields used by the extension:

```json
{
  "oauth_token": "...",
  "oauth_token_secret": "...",
  "oauth_token_expiration": 1790000000,
  "user": {
    "id": "156046",
    "screen_name": "Karan Aujla",
    "url_slug": "karanaujla"
  }
}
```

## Live protocol evidence

An anonymous signed probe of `POST access_token` with an unknown email returned:

```text
HTTP 422
{"errors":["Login validation failed: That email address was not found."]}
```

This proves the endpoint, the OAuth scheme and the form field names without using anyone’s credentials. It does **not** prove a successful login, because no authorized account is available. That remains **BLOCKED**.

## What is stored

`AudiomackSessionManager` persists only:

| Key | Value |
|---|---|
| `am_oauth_token` | Server-issued token |
| `am_oauth_token_secret` | Server-issued token secret |
| `am_oauth_token_expiration` | Expiry (epoch seconds) |
| `am_user_id` | Numeric user id |
| `am_user_slug` | Public URL slug |
| `am_user_name` | Display name |
| `am_user_cover` | Optional avatar URL |
| `am_active_user` | Echo’s selected user id |

The password is never written to Settings, logs, extras or error messages.

## Login UI

The extension implements `LoginClient.CustomInput` with one form:

- Email/username (required, email keyboard)
- Password (required, masked)

Users do not paste OAuth tokens or secrets. They sign in with their normal Audiomack credentials.

## Session lifecycle

1. `onLogin` exchanges credentials, stores the token pair + profile, and returns a `User`.
2. `setLoginUser(user)` selects the persisted session; `null` clears it.
3. `getCurrentUser()` returns the persisted user, or `null` when absent/expired.
4. If `oauth_token_expiration` has passed (with a 30-second safety margin), `load()` returns `null` and protected actions raise `LoginRequired`.
5. Logout calls `DELETE access_token`, then clears local state even if the server call fails.

There is no refresh endpoint in the official client. Re-authentication is the only supported recovery for an expired token.

## Request signing

`AudiomackOAuth.authorizationHeader` implements the OAuth 1.0a rules:

- Include `oauth_consumer_key`, `oauth_nonce`, `oauth_signature_method=HMAC-SHA1`, `oauth_timestamp`, `oauth_version=1.0`.
- Add `oauth_token` when a session exists.
- Include query parameters and form fields in the signature.
- Sort encoded parameters, build `METHOD&encodedUrl&encodedParams`, and sign with `consumerSecret&tokenSecret`.
- Percent-encode per RFC 3986 (`~` is not encoded, space is `%20`).

## Error behavior

| Condition | Result |
|---|---|
| No session on a protected call | `ClientException.LoginRequired` |
| Stored token rejected, with known user id | `ClientException.Unauthorized(userId)` |
| Anonymous request gets 401 | `LoginRequired` |
| Wrong login credentials | HTTP 422 message surfaced as an `AudiomackException` |
| Expired local token | `load()` returns `null`; protected actions request login |

For public catalog requests, the client retries once anonymously if a stored token is rejected. This keeps discovery usable while the host app decides to sign the user out. Protected requests never silently downgrade.

## Security notes

- Consumer credentials are public app identifiers, not user secrets.
- User token pairs are stored through the host’s `Settings` API.
- Signed media URLs are not persisted; they are re-resolved for each playback because they expire.
- No request or error logging includes tokens, token secrets or passwords.
