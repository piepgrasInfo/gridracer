# GridRacer Multiplayer Server

This is the authoritative Kotlin/JVM server for GridRacer multiplayer races. It keeps active game state in memory, validates every move with the shared `GameEngine`, and broadcasts state changes to connected Android clients over WebSockets. It also backs accounts, authentication, the Elo leaderboard, custom-track sharing (through a SQLite database) and race logging (to files).

**New to this?** [TUTORIAL.md](TUTORIAL.md) walks through getting a server running, playing a race across two devices, and finding and replaying that race afterwards. This document is the reference.

## Requirements

- JDK 17 or newer to *run* the server (the project targets JVM 17 bytecode). Building it with the Gradle wrapper needs no JDK of your own: the daemon toolchain is pinned to JDK 25 in `gradle/gradle-daemon-jvm.properties` and is downloaded automatically.
- Gradle (the wrapper in the repo root is enough)
- The `app` module has the `INTERNET` permission and a configurable server URL in Settings

## Running in development

The fastest way to start the server is through Gradle:

```bash
./gradlew :server:run
```

The server listens on `0.0.0.0:8080` by default and prints startup and WebSocket activity to the console through Logback. A SQLite file `gridracer.sqlite` is created in the working directory.

### Endpoints

| Endpoint | Protocol | Purpose |
|----------|----------|---------|
| `GET /health` | HTTP | Returns `OK` for load balancers and liveness checks |
| `GET /rooms` | HTTP | Returns a JSON array of open `RoomSummary` objects for the lobby list |
| `GET /play` (upgrade to WebSocket) | WebSocket | Bidirectional client/server game protocol |

### WebSocket protocol

Messages are `ClientMessage` (from the app) and `ServerMessage` (from the server), defined in `shared/src/commonMain/kotlin/info/piepgras/gridracer/multiplayer/Protocol.kt`.

**The wire format is CBOR.** The app sends CBOR binary frames, and the server **always replies in CBOR** — including to a client that sent JSON. Inbound *text* frames are decoded as JSON, so you can hand-write a message with a tool like `websocat`, but you will need a CBOR decoder to read the replies. (JSON is also what the HTTP endpoints return.)

Both decoders tolerate unknown fields *and* unknown message types — an unrecognised message decodes to `Unknown` rather than throwing, which is what lets the protocol grow without a hard version cut.

The current protocol version is **9**; the server accepts down to **2** (`MIN_SUPPORTED_PROTOCOL_VERSION`). A client below that is told to update rather than left to fail one message at a time. Protocol 9 added the gravel trap and computer levels. A client below 9 reads every race by the old rules, so the server keeps it out of any room racing with the gravel trap (on join, spectate and reconnect), refuses to switch the gravel trap on while one is in the room, and never sends it the replay of a race played by the new rules.

A typical flow looks like this:

1. Connect to `ws://<host>:8080/play`.
2. Send `ClientMessage.Hello`, then `ClientMessage.Identify` (anonymous token or session token).
3. Send `ClientMessage.Host` or `ClientMessage.Join` to enter a room.
4. Wait for `ServerMessage.RoomJoined` and `ServerMessage.LobbyUpdate`.
5. Send `ClientMessage.SetReady` when you want to start.
6. The host sends `ClientMessage.StartRace`. The server replies with `ServerMessage.PlacementStarted`.
7. During placement, send `ClientMessage.ChoosePlacement` when it is your turn.
8. When placement is complete, the server sends `ServerMessage.RaceStarted`, followed by `ServerMessage.StateSync` after each move and `ServerMessage.TurnReminder` on the active player's turn.
9. Submit moves with `ClientMessage.ChooseMove`.

AI drivers are seated by the host from the lobby (`ClientMessage.AddAiPlayer(level)`, one per free seat) and driven by the server; empty seats are simply not raced. The server no longer fills them itself. Each computer seat has a level, `EASY`, `NORMAL` or `BEST`. An `AddAiPlayer` without one (every client before protocol 9) is `EASY`, the original driver. The host changes a seat's level in the lobby with `ClientMessage.ChangeAiLevel`, and switches the gravel trap with `ClientMessage.ChangeGravelTrap` (or `Host(gravelTrap = true)`).

NORMAL and BEST drive from a planning table: the exact number of moves left from every state of the race (`shared/.../engine/planner/`). `AiPlannerService` builds the tables on two background threads (`DEFAULT_PARALLELISM`) and keeps the last 16 (`DEFAULT_CAPACITY`). A room asks for them while it is still in the lobby, so a table is normally ready by the start. A built-in track takes well under a second; a large hand-drawn one takes longer. A computer seat whose table is not ready waits up to 3 s (`Room.AI_PLAN_WAIT_MS`) *outside* the room lock, then drives as EASY. A track too large for `AiTuning.MAX_NODES` is always driven as EASY.

Three things a client implementation has to get right:

- **Deltas carry a `baseRevision`.** After the first full state, the server sends `RaceDelta`/`PlacementDelta` rather than whole states. Each one names the revision it was computed against. If that does not match the state you hold, you have missed a frame — do **not** apply it. Send `ClientMessage.RequestResync` and the server replies with the full state for the current phase. Applying a delta to the wrong base produces a wrong board that carries the server's revision number, which then passes the server's staleness check: the divergence becomes invisible to both sides.
- **Seats are renumbered.** When the roster shrinks (a kick, or someone leaving the lobby) the server compacts seat ids to `0..n-1` and sends `ServerMessage.SeatAssigned` to every connection whose id moved. `RoomJoined` is sent once and cannot carry a correction, so a client that ignores `SeatAssigned` will believe a seat number that now belongs to someone else.
- **Turns are on a clock.** `ServerMessage.TurnReminder.secondsRemaining` is the deadline the server will enforce; an unanswered turn is forfeited (see [Timeouts and reaping](#timeouts-and-reaping)). Show it.

### Server features

The server advertises its capabilities in `ServerMessage.Welcome.features`. The current build supports:

- **`accounts`** — Registration, login, session tokens, and per-account identity. Passwords are hashed with PBKDF2 (JDK `PBKDF2WithHmacSHA256`, 600k iterations). Sessions expire and are cleaned up automatically.
- **`trackSharing`** — Upload, list, download, and report custom tracks. The server rebuilds every uploaded track from its `TrackDraft` via `TrackBuilder.build` on a bounded dispatcher with a timeout, so a client-supplied compiled track is never stored or rebroadcast.
- **`privateRooms`** — Password-protected rooms. Private rooms are still *listed* by `/rooms`, with a lock flag: the code space is published by design, so the password is the security boundary and the listing is honest about that.
- **`leaderboard`** — Elo ratings for matchmade races, persisted in SQLite. Only matchmade rooms with two or more account-holding players are rated; private rooms and guests are not.
- **`accountDeletion`** — `ClientMessage.DeleteAccount` erases an account and everything stored against it. See [Deleting an account](#deleting-an-account).
- **`gravelTrap`** — Rooms may race with the gravel trap: a band beyond the outer wall that brakes a car instead of crashing it. The host chooses it per room (`Host.gravelTrap`, `ChangeGravelTrap`), and `LobbyUpdate`/`RoomSummary` carry it. Matchmade rooms race without it. The app shows the switch only when the server advertises this.
- **`aiLevels`** — Computer seats take a level (`AddAiPlayer(level)`, `ChangeAiLevel`), and NORMAL and BEST seats are driven from planning tables, as described above. Without this feature the app adds plain `AddAiPlayer` seats, which are EASY.

## Deleting an account

Google Play requires any app that lets a user create an account to offer a way to delete it, in the app and at a web URL. The app's path is **Settings → Delete account…**; the hosted counterpart is `legal/delete-account.md`, published at `piepgras.info/legal/grid-racer/delete-account/`.

On the wire it is `ClientMessage.DeleteAccount(password)` → `ServerMessage.AccountDeleted(token)`. The password is re-entered rather than taken on the session's word: a session token lives 90 days on a device and must not be enough on its own to destroy an account. The request runs on the same rate budget as login, and is refused with `ALREADY_IN_ROOM` while the caller is seated in a room — a seat caches the account id it was taken with, and the player leaves the race first.

What one deletion does:

- **In one transaction** (`AuthService.deleteAccount`) the `accounts` row goes, and `ON DELETE CASCADE` takes its sessions, the tracks it shared (with their ratings and reports), and the ratings and reports it filed on other people's tracks. The denormalized `rating_sum`/`rating_count` columns on the tracks it had rated are then recomputed — the cascade removes the rating rows but knows nothing about the aggregate, and a track left advertising a rating nobody gave it is the bug this step exists to prevent.
- **Seats are cleared** (`RoomManager.forgetAccount`) anywhere on the server, including rooms joined from another device, so no seat outlives the account it names.
- **Race logs are scrubbed** (`RaceLogger.purgeAccount`) on the log writer's own thread: the deleted player's seat is renamed to "Deleted player" and its public account id dropped, in both the replay files and `races.jsonl`. The races themselves are kept — a replay belongs to everyone who drove in it.

The scrub is best-effort follow-up work; the transaction is what the user asked for, and it has already committed by the time the logs are walked. Failures are logged, not reported to the client.

The username becomes free for registration again. There is no grace period and no soft-delete flag: "delete my account" is answered literally.

## Race logging

Every finished race is written to disk in the app's own replay format, so a race can be watched back rather than only argued about. This is on by default; `GRIDRACER_RACE_LOG=off` disables it entirely.

### What it writes

```
races/
├── 2026-08-27T14-02-11Z_AB3XK.gridracer-replay   # one file per race
├── 2026-08-27T14-09-40Z_QM7RT.gridracer-replay
└── races.jsonl                                   # one summary line per race
```

A `.gridracer-replay` file is a JSON `ReplayData`: the starting `GameState`, every move with the RNG seed the engine was given, and the room's provenance (room code, server id and name, protocol version, whether the race was matchmade, and the *opaque public* account id per seat — never a token, never an internal row id).

Because each move carries its seed, folding the moves back over the starting state reproduces the race **exactly**, including oil-slip outcomes. That is asserted by `RaceLoggerTest`, which plays a race through a real `Room` and checks the reconstruction lands on the same standings the server actually broadcast.

`races.jsonl` is an index for scanning: room code, track, file name, start/finish times, move count and final standings, one JSON object per line. It holds no move data — the replay file is the record.

### Watching a logged race

The file is byte-for-byte what the app's replay browser reads, so there is no converter:

```bash
# Copy a race off the server
scp your-vps:/var/lib/gridracer/races/2026-08-27T14-02-11Z_AB3XK.gridracer-replay .

# Push it to a device and open it from the app
adb push 2026-08-27T14-02-11Z_AB3XK.gridracer-replay /sdcard/Download/
```

Then in the app: **Replay Browser → Import…**, pick the file, and play it.

To find a race first:

```bash
# The last ten races
tail -n 10 /var/lib/gridracer/races/races.jsonl | jq -c '{roomCode, trackName, file, winner: .standings[0].name}'

# Every race someone played in
jq -c 'select(.standings[].name == "Martin")' /var/lib/gridracer/races/races.jsonl
```

### Retention

Logs older than `GRIDRACER_RACE_LOG_RETENTION_DAYS` (30 by default) are deleted by the same housekeeping pass that reaps idle rooms, and the `races.jsonl` lines pointing at them go too — the index carries display names, which is exactly what the retention window exists to expire. Setting it to `0` keeps everything forever — deliberate, but say so on purpose: race logs contain display names and are personal data, and the privacy policy states the retention window.

### Writing is off the hot path

`Room.onRaceFinished` runs under the room mutex, so it only *enqueues*; a single writer thread does the file I/O. A slow disk cannot hold up a room — or, through `RoomManager`, the `/rooms` listing for the whole server. If races finish faster than the disk absorbs them, the newest log line is dropped and a warning is logged: a race log is diagnostic, and blocking the game to protect it would be the wrong trade.

## Building a distribution

To build the installable distribution with start scripts:

```bash
./gradlew :server:installDist
```

Run the produced distribution:

```bash
server/build/install/server/bin/server
```

The path also includes a `.bat` file for Windows.

You can package the same distribution as a ZIP or TAR with:

```bash
./gradlew :server:distZip
./gradlew :server:distTar
```

## Configuration

The server reads configuration from environment variables:

| Variable | Default | Purpose |
|----------|---------|---------|
| `PORT` | `8080` | Port to listen on |
| `HOST` | `0.0.0.0` | Bind address. Use `127.0.0.1` for local-only |
| `GRIDRACER_DB_PATH` | `gridracer.sqlite` | SQLite file path. Use `:memory:` for ephemeral (no persistence) |
| `GRIDRACER_TRUST_PROXY` | `false` | Trust `X-Forwarded-For` for client IP. Set to `true` only behind a reverse proxy |
| `GRIDRACER_SERVER_ID` | derived | Stable id for this deployment, used to namespace shared-track UUIDs across servers. Defaults to a UUIDv5 of the absolute database path, so it survives restarts without configuration |
| `GRIDRACER_SERVER_NAME` | `GridRacer` | Human-readable name shown next to track authorship and on logged races |
| `GRIDRACER_RACE_LOG` | `on` | Set to `off` (or `false`/`0`/`no`) to stop recording races. Anything else leaves it on — a typo must not silently disable it |
| `GRIDRACER_RACE_LOG_DIR` | `races` | Where race logs are written. Relative paths resolve against the working directory |
| `GRIDRACER_RACE_LOG_RETENTION_DAYS` | `30` | Age at which a race log is deleted. `0` keeps everything forever; a malformed value falls back to the default rather than to "keep forever" |
| `JAVA_TOOL_OPTIONS` / `JDK_JAVA_OPTIONS` | — | Standard JVM flags for memory limits, GC, etc. |

Example:

```bash
PORT=9000 GRIDRACER_DB_PATH=/var/lib/gridracer/gridracer.sqlite ./gradlew :server:run
```

## Self-hosting / LAN

1. Build or run the server on a machine on the same network.
2. Note its IP address, for example `192.168.1.42`.
3. In the Android app, open **Settings → Multiplayer server** and set:

```
ws://192.168.1.42:8080/play
```

4. Tap **Multiplayer** on the main menu. One device hosts a room, others join by room code.

`ws://` is unencrypted, and the app says so: Settings shows a warning above the login form,
the account-deletion dialog repeats it next to the password field, and the race setup shows an
open padlock instead of a closed one. That is fine on a network you trust. For anything
reachable from the internet, put Caddy in front (step 5 below) and use `wss://`. A self-signed
certificate will not do: the app checks the chain against the device's trusted CAs and the host
name against the certificate, and refuses the connection otherwise.

## The deployment this app actually talks to

The walkthrough below is generic. The instance the shipped app points at is:

| | |
|---|---|
| URL in the app | `wss://gridracer.piepgras.info/play` from 1.3 (`AppSettings.PUBLIC_SERVER_URL` in `shared/engine/Settings.kt`); `ws://gridracer.piepgras.info:5521/play` in 1.0 to 1.2 |
| TLS / reverse proxy | **Caddy**, on 443 with an automatic certificate, and on a plain **5521** for the versions before 1.3. Both proxy to the server on `127.0.0.1:8080` |
| Server environment | `HOST=127.0.0.1`, `PORT=8080`, `GRIDRACER_TRUST_PROXY=true` |
| Ports open to the internet | 80 (certificate challenge and the redirect), 443, 5521, and never 8080 |

The Caddyfile for it, the whole of what this deployment needs:

```
gridracer.piepgras.info {                 # 443, TLS with an automatic certificate
    reverse_proxy 127.0.0.1:8080          # 1.3 and later: wss://gridracer.piepgras.info/play
}

http://:5521 {                            # plain, for 1.0 to 1.2 until they update
    reverse_proxy 127.0.0.1:8080
}
```

The plain block names only the port, so it answers whatever host name or IP address an old
app was given, as the server did when it listened on 5521 itself.

Four things depend on each other here:

- **`GRIDRACER_TRUST_PROXY=true` is safe only because every path goes through
  Caddy.** The server listens on 127.0.0.1, so no client can reach it directly
  and hand it its own `X-Forwarded-For`; Caddy overwrites that header for clients
  it does not trust, and the server reads the rightmost entry. Expose the server
  directly again (on 0.0.0.0, or by forwarding 8080) and the variable must go
  back to `false`, or the per-IP rate limits can be walked straight past. Leaving
  it `false` behind Caddy is wrong the other way: every player then shares
  Caddy's address, `127.0.0.1`, and one rate-limit bucket.
- **Old versions are pinned to 5521.** 1.0 to 1.2 shipped with the plain address
  as their default, and nothing but an update moves them. 1.3 moves them on its
  own: a stored `ws://gridracer.piepgras.info:5521/play` is rewritten to the
  `wss://` address when the settings load (`AppSettings.withCurrentServerUrl`),
  without logging anyone out. Retire 5521 (drop the second block, close the port)
  only once Play's statistics show no installs below 1.3, and change the Privacy
  Policy's *Encryption in transit* in the same step, because it says the port
  stays open.
- **Deploy before the app.** 1.3 has no fallback to `ws://` (a silent fallback is
  a downgrade attack), so if 443 does not answer, 1.3 cannot play online at all.
  Check `wss://` from outside the network before rolling the release out.
- **No `log` directive for this site.** The Privacy Policy describes the
  server's own rate-limit log and nothing else. A Caddy access log would be a
  second record of every player's IP address; if you add one, the policy's
  *Server logs* section has to say so first.

## Deploying on a VPS

This section walks through setting up the server as a production service on a Linux VPS with TLS, automatic restarts, and a persistent database.

### 1. Install JDK 17

```bash
sudo apt update
sudo apt install -y openjdk-17-jre-headless
```

Verify:

```bash
java -version
```

### 2. Create a dedicated user and directories

```bash
sudo useradd --system --no-create-home --shell /usr/sbin/nologin gridracer
sudo mkdir -p /opt/gridracer /var/lib/gridracer
sudo chown gridracer:gridracer /var/lib/gridracer
```

### 3. Build the distribution and upload it

On your development machine:

```bash
./gradlew :server:installDist
tar -czf server-dist.tar.gz -C server/build/install server
```

Copy to the VPS:

```bash
scp server-dist.tar.gz youruser@your-vps:/tmp/
```

On the VPS, extract and install:

```bash
sudo tar -xzf /tmp/server-dist.tar.gz -C /opt/gridracer --strip-components=1
sudo chown -R gridracer:gridracer /opt/gridracer
sudo chmod +x /opt/gridracer/bin/server
```

The layout is:

```
/opt/gridracer/
├── bin/
│   └── server          # start script
└── lib/
    └── *.jar           # server + dependencies
```

### 4. Create a systemd service

```bash
sudo tee /etc/systemd/system/gridracer.service > /dev/null << 'EOF'
[Unit]
Description=GridRacer Multiplayer Server
After=network.target

[Service]
Type=simple
User=gridracer
Group=gridracer
WorkingDirectory=/var/lib/gridracer
ExecStart=/opt/gridracer/bin/server
Environment=HOST=127.0.0.1
Environment=PORT=8080
Environment=GRIDRACER_DB_PATH=/var/lib/gridracer/gridracer.sqlite
Environment=GRIDRACER_TRUST_PROXY=true
Environment=GRIDRACER_SERVER_NAME=My GridRacer
Environment=GRIDRACER_RACE_LOG_DIR=/var/lib/gridracer/races
Environment=JAVA_OPTS=-Xmx256m
Restart=on-failure
RestartSec=5

# Hardening
NoNewPrivileges=true
ProtectSystem=strict
ProtectHome=true
ReadWritePaths=/var/lib/gridracer
PrivateTmp=true

[Install]
WantedBy=multi-user.target
EOF
```

Notes on the configuration:

- `HOST=127.0.0.1` — bind to localhost only; the reverse proxy handles external traffic.
- `GRIDRACER_TRUST_PROXY=true` — trust `X-Forwarded-For` from the reverse proxy for per-IP rate limiting. **Only set this if a reverse proxy is in front.**
- `JAVA_OPTS=-Xmx256m` — cap heap at 256 MB. Adjust for your VPS size.
- `ReadWritePaths=/var/lib/gridracer` — the only directory the service can write to. Both the SQLite file and the race logs live under it; if you move `GRIDRACER_RACE_LOG_DIR` elsewhere, add that path here too or the server will not be able to write logs.
- `GRIDRACER_SERVER_NAME` — shown next to shared-track authorship and stamped on every logged race. Worth setting if you run more than one server.

Enable and start:

```bash
sudo systemctl daemon-reload
sudo systemctl enable gridracer
sudo systemctl start gridracer
```

Check status and logs:

```bash
sudo systemctl status gridracer
sudo journalctl -u gridracer -f
```

### 5. Set up a reverse proxy with TLS

The server speaks plain HTTP/WS. For a public server, put a TLS-terminating reverse proxy in front so clients connect via `wss://`.

#### Option A: Caddy (automatic HTTPS)

Install Caddy per [the official instructions](https://caddyserver.com/docs/install). Then:

```bash
sudo tee /etc/caddy/Caddyfile > /dev/null << 'EOF'
gridracer.example.com {
    reverse_proxy /play localhost:8080
    reverse_proxy /rooms localhost:8080
    reverse_proxy /health localhost:8080
}
EOF
sudo systemctl reload caddy
```

Caddy automatically provisions and renews a Let's Encrypt certificate. No manual cert management needed.

#### Option B: nginx + Certbot

Install nginx and certbot:

```bash
sudo apt install -y nginx certbot python3-certbot-nginx
```

Create the site config:

```bash
sudo tee /etc/nginx/sites-available/gridracer > /dev/null << 'EOF'
server {
    listen 80;
    server_name gridracer.example.com;

    location /play {
        proxy_pass http://localhost:8080;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_read_timeout 86400;
    }

    location /rooms {
        proxy_pass http://localhost:8080;
    }

    location /health {
        proxy_pass http://localhost:8080;
    }
}
EOF
sudo ln -s /etc/nginx/sites-available/gridracer /etc/nginx/sites-enabled/
sudo nginx -t && sudo systemctl reload nginx
sudo certbot --nginx -d gridracer.example.com
```

Certbot will provision the certificate and set up automatic renewal.

### 6. Open the firewall

If `ufw` is enabled, allow only HTTPS (the reverse proxy handles the rest):

```bash
sudo ufw allow 443/tcp
sudo ufw reload
```

Do **not** open port 8080 — the server is bound to `127.0.0.1` and should only be reachable through the proxy.

### 7. Point the app at the server

In the Android app, open **Settings → Multiplayer server** and set:

```
wss://gridracer.example.com/play
```

### 8. Verify

From the VPS:

```bash
curl http://localhost:8080/health
# OK
```

From outside:

```bash
curl https://gridracer.example.com/health
# OK
```

### 9. Back up the database

The SQLite file at `/var/lib/gridracer/gridracer.sqlite` holds all accounts, sessions, ratings, and shared tracks. Back it up periodically:

```bash
sudo cp /var/lib/gridracer/gridracer.sqlite /backup/gridracer-$(date +%F).sqlite
```

Or use `sqlite3` for a safe online backup:

```bash
sqlite3 /var/lib/gridracer/gridracer.sqlite ".backup /backup/gridracer.sqlite"
```

### 10. Update the server

```bash
# On your dev machine
./gradlew :server:test            # run online; --offline breaks on sqlite-jdbc
./gradlew :server:installDist
tar -czf server-dist.tar.gz -C server/build/install server
scp server-dist.tar.gz youruser@your-vps:/tmp/
```

On the VPS, first read what is actually installed rather than trusting this file:

```bash
systemctl cat gridracer          # unit name, paths, user, environment
```

Back up before swapping anything. The database is in WAL mode, so copying the
`.sqlite` file alone can miss committed data still sitting in the `-wal`:

```bash
sudo sqlite3 /var/lib/gridracer/gridracer.sqlite ".backup /var/backups/gridracer-$(date +%F).sqlite"
sudo tar -czf /var/backups/gridracer-races-$(date +%F).tar.gz -C /var/lib/gridracer races
sudo cp -a /opt/gridracer /opt/gridracer.bak    # the rollback
```

Then swap and restart:

```bash
sudo systemctl stop gridracer
sudo rm -rf /opt/gridracer/lib                  # optional: drop jars the new build no longer uses
sudo tar -xzf /tmp/server-dist.tar.gz -C /opt/gridracer --strip-components=1
sudo chown -R gridracer:gridracer /opt/gridracer
sudo chmod +x /opt/gridracer/bin/server
sudo systemctl start gridracer
sudo systemctl status gridracer
sudo journalctl -u gridracer -n 50 --no-pager
```

Rolling back is `sudo rm -rf /opt/gridracer && sudo mv /opt/gridracer.bak /opt/gridracer`
and a restart — safe whenever the update carried no migration (check whether the
commit touched `db/Database.kt`; `MIGRATIONS` there is the schema, the DAOs are not).

The database itself is untouched by the swap — schema migrations run automatically
on startup via `PRAGMA user_version`, and the server refuses to start if the
on-disk schema is *newer* than the build, which is what makes a rollback across a
migration the one case to think twice about.

**Verifying you are running the new build.** `/health` returns `OK` from any
version, so it proves nothing:

```bash
curl -s http://localhost:5521/health    # OK - from old and new alike
```

The `Welcome` handshake is the real check: its `features` list must match
`SUPPORTED_FEATURES` in `MessageRouter.kt`. Note that `protocolVersion` is
usually *absent* from the reply — `encodeDefaults` is false, so a server sitting
at its own default omits it — which is exactly why the feature list, not the
version number, is the thing to read. The socket accepts a JSON text frame as
well as CBOR, so one frame is enough to ask:

```json
{"type":"info.piepgras.gridracer.multiplayer.ClientMessage.Hello","protocolVersion":9}
```

The reply comes back as a binary CBOR frame. From the app, the same question is
**Settings → Delete account…**: the password field means the server advertises
`accountDeletion`; the "request deletion by mail" text means it does not, and the
server is older than protocol 8. For protocol 9, choose **Start Race → Host online**. A *Gravel
trap* switch in the race settings means the server advertises `gravelTrap`, and a level on each
computer seat in the lobby means `aiLevels`. Without them, the server is older and must be
redeployed before the 1.3 app can race the gravel trap online.

## Database

The server uses a single SQLite JDBC connection (`org.xerial:sqlite-jdbc`) with WAL mode, foreign keys, and a busy timeout. All database access runs on a dedicated single-thread dispatcher so no DB call blocks a WebSocket coroutine.

Schema migrations are versioned via `PRAGMA user_version` and run automatically on startup. The server fails fast if the on-disk schema is newer than the running build (downgrading is not supported).

Current tables:

| Table | Purpose |
|-------|---------|
| `accounts` | User accounts with username, password hash, display name, Elo rating, matches, wins |
| `sessions` | Login session tokens with expiry |
| `shared_tracks` | Uploaded custom tracks (draft + server-rebuilt track as JSON) |
| `track_reports` | User-submitted reports against shared tracks, for manual review |
| `track_ratings` | One 1–5 rating per account per track; re-rating replaces. `shared_tracks` carries denormalised `rating_sum`/`rating_count` so the list query does not hit this table per row |

## Room codes and lifecycle

- A room is created when a player hosts. The server returns a **five**-character code such as `AB3XK`, drawn from `A–Z` and `2–9` — no `0` or `1`, because codes get read aloud and those read as `O` and `I`. A typed code is folded the same way, so a code written down with a zero still works.
- The room stays in the lobby list until it starts or the host leaves.
- When the host **explicitly leaves**, the server promotes the next eligible player to host (sending `ServerMessage.HostPromoted`). If nobody can be promoted, the room closes at once.
- When the host **drops** (a transient disconnect), the room is *not* closed. Closing it made the advertised reconnect unreachable for a solo host: `Reconnect` is refused on a finished room, so a phone changing networks lost the room and everyone waiting in it. The room is held for the empty-room grace period and reaped by the idle sweep if nobody comes back.
- When a non-tournament race finishes, the server sends `ServerMessage.RaceFinished` with the final standings and transitions the room back to the lobby. The host sends `ClientMessage.ReturnToLobby` to bring everyone off the results screen. The room stays alive until the host leaves.
- When a tournament finishes, the server sends `ServerMessage.TournamentFinished` and closes the room.

## Timeouts and reaping

The server never waits on a client forever. A minute-by-minute housekeeping pass closes what is finished and what has been abandoned.

| Rule | Limit | What happens |
|------|-------|--------------|
| Turn timeout | 20 seconds by default; the host can set 5–20 s per room from the lobby (`ChangeTurnTimeoutSeconds`) | A human whose turn goes unanswered forfeits it; the race continues without them. The deadline is sent in `TurnReminder.secondsRemaining` so the client can show a countdown. |
| Placement timeout | 45 seconds | A start slot is auto-chosen. Everyone else in the room is watching a spinner until it is picked. |
| Empty-room grace | 2 minutes | A room whose humans have all dropped is closed. Short, because the usual cause is a phone changing networks and the player is about to reconnect. |
| Idle lobby | 30 minutes | A lobby where nothing has happened is closed. |
| Idle race | 15 minutes | A race with no activity is closed. The per-turn forfeit should have caught this, so reaching it means the room is stuck in a way the turn timer cannot see. |
| Matchmaking TTL | 5 minutes | A queue entry whose bucket never fills is dropped and the player is told. |

All six live in `server/.../security/Limits.kt`.

## Limits and abuse protection

Every bound the server enforces is in one file, `server/.../security/Limits.kt`, and every rate budget is in `server/.../security/RateLimiter.kt`.

Rate limiting works in **two dimensions**, and both matter:

- **Per connection**, keyed by an internal id. Bounds what one socket can do.
- **Per address**, keyed by the resolved client IP. This is the one that actually binds on a determined client: the per-connection id is a fresh UUID, so anyone who wants more allowance only has to open another socket. Per-IP budgets are set several times looser than their per-connection twins, because a household, a school or a mobile carrier's CGNAT pool all present as one address.

On top of those, concurrent connections are capped **per address** (`MAX_CONNECTIONS_PER_IP`, 16) and **in total** (`MAX_TOTAL_CONNECTIONS`, 2000) — a budget bounds traffic, only a count bounds file descriptors.

Note that per-IP limits are only as good as the address the server sees. Behind a reverse proxy you **must** set `GRIDRACER_TRUST_PROXY=true`, or every request appears to come from the proxy and shares one budget. Without a proxy in front you must **not** set it, since a directly-connected client can send `X-Forwarded-For` itself.

Payload bounds worth knowing:

| Bound | Value | Why |
|-------|-------|-----|
| `MAX_FRAME_BYTES` | 512 KiB | The largest legitimate frame is a tournament of 8 tracks. Ktor closes anything larger cleanly instead of risking an OOM. |
| `MAX_WALLS` | 4000 | A hosted track is rebroadcast to every player and spectator on every lobby update, so an oversized one is an amplification vector. Applied to hosted *and* uploaded *and* mid-lobby-swapped tracks. |
| `MAX_TOTAL_ROOMS` | 500 | Server-wide room cap. |
| `MAX_SPECTATORS_PER_ROOM` | 20 | |

A client that stops reading its socket is disconnected rather than allowed to stall its room: outbound messages go through a bounded per-connection queue, and a full queue closes the connection.

## Quick reference: running the server

### With Gradle (development)

```bash
./gradlew :server:run                              # default: 0.0.0.0:8080, gridracer.sqlite in CWD
PORT=9000 ./gradlew :server:run                     # custom port
GRIDRACER_DB_PATH=/data/gridracer.sqlite ./gradlew :server:run
```

### With the distribution (production)

```bash
./gradlew :server:installDist                      # build once
server/build/install/server/bin/server              # run the start script
```

The distribution is self-contained — all dependency JARs are in `server/build/install/server/lib/`.

### With systemd (recommended for VPS)

See the [Deploying on a VPS](#deploying-on-a-vps) section above for the full guide. Quick commands:

```bash
sudo systemctl start gridracer
sudo systemctl stop gridracer
sudo systemctl restart gridracer
sudo systemctl status gridracer
sudo journalctl -u gridracer -f                     # tail logs
```

### With cron (not recommended for a long-running server)

The GridRacer server is a long-running process, so **systemd is strongly preferred**. Cron is only suitable as a watchdog that restarts the server if it has died. Do not use cron to start the server on a schedule — it will spawn duplicate instances.

If you cannot use systemd, a cron watchdog looks like this:

```bash
# Add to the gridracer user's crontab (crontab -e)
*/5 * * * * pgrep -f "gridracer.*bin/server" > /dev/null || cd /var/lib/gridracer && /opt/gridracer/bin/server >> /var/log/gridracer.log 2>&1
```

This checks every 5 minutes and starts the server only if it isn't already running. Set the environment variables in a wrapper script instead of inline:

```bash
# /opt/gridracer/start.sh
#!/bin/sh
export HOST=127.0.0.1
export PORT=8080
export GRIDRACER_DB_PATH=/var/lib/gridracer/gridracer.sqlite
export GRIDRACER_TRUST_PROXY=true
cd /var/lib/gridracer
exec /opt/gridracer/bin/server
```

Then the crontab entry becomes:

```
*/5 * * * * pgrep -f "gridracer.*bin/server" > /dev/null || /opt/gridracer/start.sh >> /var/log/gridracer.log 2>&1
```

## Runtime files checklist

The server needs the following files and directories at runtime:

| Path | Purpose | Created by |
|------|---------|------------|
| `gridracer.sqlite` (or `GRIDRACER_DB_PATH`) | SQLite database: accounts, sessions, Elo ratings, shared tracks, track reports | Server on first run |
| `gridracer.sqlite-wal` | SQLite write-ahead log (WAL mode sidecar) | Server at runtime |
| `gridracer.sqlite-shm` | SQLite shared memory file (WAL mode sidecar) | Server at runtime |
| `races/` (or `GRIDRACER_RACE_LOG_DIR`) | Race logs: one `.gridracer-replay` per race plus `races.jsonl` | Server, on the first finished race |
| `/opt/gridracer/` | Distribution directory (bin/ + lib/) | You (from `installDist`) |
| `/var/lib/gridracer/` | Working directory + database location | You (mkdir) |
| Log output | Goes to STDOUT by default | journalctl (systemd) or redirect (cron) |

**No config files are needed** — all configuration is via environment variables (see [Configuration](#configuration)).

**Back up** the `.sqlite` file regularly. The `-wal` and `-shm` files are transient and will be recreated; do not back them up separately. For a safe online backup:

```bash
sqlite3 /var/lib/gridracer/gridracer.sqlite ".backup /backup/gridracer.sqlite"
```

**Do not delete** the `.sqlite` file — it contains all user accounts, ratings, and community-shared tracks.

**Race logs are not in the database.** `sqlite3 .backup` does not cover them; back up the race-log directory separately if you want to keep them, or let the retention sweep expire them. They are recoverable in no other way — the server keeps nothing else about a finished race beyond the leaderboard totals.

## Testing with curl

Check the server is running:

```bash
curl http://localhost:8080/health
```

List open rooms:

```bash
curl http://localhost:8080/rooms
```

The HTTP endpoints are the easy ones to poke by hand. The WebSocket endpoint replies in CBOR, so a plain
[websocat](https://github.com/vi/websocat) session will show you binary:

```bash
websocat ws://localhost:8080/play
```

You can *send* JSON on a text frame (the server decodes text frames as JSON), but decoding the replies needs a
CBOR tool. In practice the protocol is easier to exercise from `server/src/test/` — `MessageRouterTest` drives
every message through a fake connection with no sockets involved at all.

## Monitoring

The server uses Logback for runtime logging. By default, logs go to STDOUT (visible via `journalctl -u gridracer`). Production deployments can mount a custom `logback.xml` on the classpath or set the `logback.configurationFile` system property.
