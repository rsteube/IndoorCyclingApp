# A throwaway FitTrackee, for testing the integration

The FitTrackee integration talks to a server the rider runs, so the only
honest way to test it is against a real one.

```powershell
docker compose -f dev/fittrackee/docker-compose.yml up -d
```

Then in the app: **Settings → Connections → FitTrackee**, server address
`https://10.0.2.2` (an emulator's route to the host).

## Why there is a TLS proxy in here

`HttpFetcher` refuses `http://` on every method, and the OAuth flow carries a
client secret and tokens. Rather than weaken that check for testing, Caddy puts
real TLS in front of FitTrackee using its internal CA, and the **debug** build
trusts that CA via `mobile_clients/android/src/debug/res/xml/network_security_config.xml`.
Release builds neither contain nor reference it.

Two things that are easy to trip over, both already handled here:

- `default_sni` is set, because clients send no SNI when connecting to a bare
  IP address and Caddy otherwise aborts the handshake with an internal error.
- The database image is PostGIS, not plain Postgres: FitTrackee's migrations
  add a `geometry` column and fail outright without the extension.

## Creating a test account and an OAuth app

Registration needs no mail server (email sending is off, so accounts just need
activating), and FitTrackee will not let a client register itself, so both are
done up front:

```bash
curl -k -X POST https://localhost/api/auth/register -H "Content-Type: application/json" \
  -d '{"username":"rider","email":"rider@example.org","password":"indoorbike-test-1","accepted_policy":true}'

docker compose -f dev/fittrackee/docker-compose.yml exec fittrackee ftcli users update rider --activate

# log in for a token, then create the OAuth app with the redirect URI the app uses
curl -k -X POST https://localhost/api/oauth/apps -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"client_name":"Indoor Bike","client_uri":"https://indoorbike.app",
       "redirect_uris":["indoorbike://oauth"],
       "scope":"profile:read workouts:read workouts:write"}'
```

`workouts:read` is not optional despite the app never reading a ride back:
`GET /api/sports` is gated behind it, and without it the sport picker is
permanently empty and no ride can be filed.

## What a successful upload looks like

FitTrackee rejects the FIT file (`no valid segments with GPS found in fit
file`) and the app falls back to `POST /api/workouts/no_gpx`, so the ride lands
with its date, duration, estimated distance and the figures in its notes. Note
that the workout *list* endpoint omits `notes`; fetch a single workout to see
them.

Tear down with `docker compose -f dev/fittrackee/docker-compose.yml down -v`.
