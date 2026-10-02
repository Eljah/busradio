# HTTP API, schema 1

All modifying requests use Authorization: Bearer TOKEN. Admin and per-device tokens are distinct. Device requests additionally use `X-Bus-Id`. Content-Type is `application/json` except raw asset upload. Browser token is held only in JS memory. Production requires TLS on the reverse proxy; direct HTTP is allowed by the client only for loopback unless explicitly overridden.

| Method | Endpoint | Request / result |
|---|---|---|
| GET | `/health` | Public readiness response |
| GET | `/api/admin/state` | Assets, playlists, campaigns, routes, assignments; no plaintext device keys |
| POST | `/api/admin/assets?name=...&kind=MUSIC` | Raw media bytes; MUSIC or AD; canonical WAV stored, Asset returned |
| PUT | `/api/admin/playlist` | Full Playlist record |
| PUT | `/api/admin/campaign` | Full Campaign record |
| PUT | `/api/admin/route` | Full Route record |
| POST | `/api/admin/import-buscrawl` | Original routes.json |
| POST | `/api/admin/device` | `{id,playlistId,routeId,direction}`; returns newly generated key; existing key revoked on reenrolment |
| PUT | `/api/admin/assignment` | Same request; preserves existing device key |
| GET | `/api/admin/events` | Latest 200 uploaded events |
| GET | `/api/admin/heartbeats` | Latest 500 stored heartbeat files |
| GET | `/api/device/manifest` | Validated Manifest with assets, playlist, campaigns and both route directions; ETag, If-None-Match |
| GET | `/api/device/assets/{sha256}` | Only assets assigned to this device; Range and If-Range supported |
| POST | `/api/device/events` | Array of Event; returns accepted IDs for durable queue acknowledgement |
| POST | `/api/device/heartbeat` | Device status; bus ID bound to authenticated device |

## Records

```json
{"id":"daily","name":"Дневная программа","tracks":["asset-id"],"adEveryTracks":3,"adWindowSeconds":60,"maxAds":2}
```

```json
{"id":"r50","number":"50","direction":0,"geometry":"polyline","points":[{"lat":55.796,"lon":49.100},{"lat":55.796,"lon":49.130}]}
```

```json
{"id":"cafe","name":"Кафе у остановки","assetId":"ad-asset-id","routeId":"r50","direction":0,"lat":55.796,"lon":49.112,"radiusMeters":600,"aheadMeters":1200,"aheadOnly":true,"cooldownSeconds":1800,"maxPerDay":8,"priority":0,"startsAt":"2026-10-01T00:00:00Z","endsAt":"2027-01-01T00:00:00Z"}
```

These are schema examples, not preloaded actual commercial locations. Direction `-1` means any direction; empty campaign routeId means any route. Route geometries must be oriented in the travel direction. `stop-chain` marks approximate geometry imported from crawler. Campaign bounds, playlist media references and device assignments are validated; campaign route IDs should be selected from the imported route catalog. Full records are required; unknown or missing record fields are rejected.

GPS file-source Fix:

```json
{"lat":55.796,"lon":49.110,"speedMps":8.0,"bearing":90.0,"accuracyMeters":5.0,"time":"2026-10-02T01:00:00Z"}
```

Use the actual measurement time. An old file will suppress ads, not magically become current. GPS coordinates are restricted to |latitude| <= 85 degrees for the local projection used here.

Asset normalization accepts WAV/MP3/FLAC/OGG up to 256 MiB; resulting PCM must be <=15 minutes and <=256 MiB. Stereo 16-bit 44.1 kHz PCM uses about 176,400 bytes per second, so capacity planning matters. Playlist and campaign times are metadata derived from canonical PCM, not operator-entered fake durations.

Event statuses: STARTED, COMPLETED, FAILED, SKIPPED. UUID is the idempotency key. Server rejects the same ID with different contents and bus-ID spoofing. Queue acknowledgement means stored event, not verified audience reach. Data retention/backups are the operator's responsibility.
