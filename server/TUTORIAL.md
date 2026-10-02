# Running your own GridRacer server — a walkthrough

This is the path. [README.md](README.md) is the reference: when you want to know what a
setting does, look there; when you want to get a server running for the first time, start here
and go top to bottom.

Roughly 20 minutes for parts 1–4 (a server on your own network), plus 20 for part 5 if you
want it reachable from the internet.

**What you need:** a computer with JDK 17 or newer, an Android device or emulator with Grid
Racer installed, and both on the same Wi-Fi. Part 5 additionally needs a Linux VPS and a
domain name.

---

## 1. Start a server

From the repository root:

```bash
./gradlew :server:run
```

You will see something like:

```
GridRacer server starting on 0.0.0.0:8080
Race logging is on: writing to /home/you/GridRacer/races, keeping 30 days
```

That is it — the server is running. It has created `gridracer.sqlite` in the working directory
for accounts, ratings and shared tracks, and it will create a `races/` directory the first time
a race finishes.

Leave it running and open a second terminal.

### Check it is alive

```bash
curl http://localhost:8080/health
# OK

curl http://localhost:8080/rooms
# []
```

An empty array is correct: nobody has hosted a room yet.

(The `/play` WebSocket endpoint replies in CBOR, so poking *that* one by hand needs a CBOR
decoder. `/health` and `/rooms` are plain HTTP and JSON, which is why they are the ones to use
for a smoke test.)

> **Stuck?** If Gradle cannot resolve dependencies, you are offline — the first run needs to
> download Ktor and the SQLite driver. If the port is taken, run
> `PORT=9000 ./gradlew :server:run` and use 9000 everywhere below.

---

## 2. Point a phone at it

Find your computer's address on the local network:

```bash
# Linux
ip -4 addr show | grep -oP '(?<=inet\s)192\.168\.[0-9.]+'
# macOS
ipconfig getifaddr en0
```

Say it is `192.168.1.42`. On the phone, open Grid Racer and go to
**Settings → Server → Multiplayer server**, and enter:

```
ws://192.168.1.42:8080/play
```

Leave the field (tap elsewhere) — the address is committed when the field loses focus, not on
every keystroke, because each change rebuilds the connection.

The status line under the field should turn to **Connected** within a second or two.

> **Stuck?** Still "Disconnected"?
> - `ws://`, not `http://` or `wss://`. Plain `ws` is correct for a local server with no TLS.
> - The path is `/play`. Without it the server has nothing to answer.
> - Your firewall may be blocking port 8080 on the LAN. On Linux:
>   `sudo ufw allow from 192.168.0.0/16 to any port 8080`.
> - Phone and computer must be on the same network — many guest Wi-Fi networks isolate clients
>   from each other.

---

## 3. Host a race and join it

You need two players. Use two devices, or one device plus an emulator, or two emulators.

**On the first device:**

1. **Start Race → Host**.
2. Pick a track and how many seats. Leave the password blank for now.
3. Tap **Host race**.

You are in the lobby. On the right you will see the **room code** — five characters like
`AB3XK`. **Copy** puts it on the clipboard; **Share** sends it through any messaging app.

Watch the server log:

```
Room AB3XK created by host (private=false)
```

**On the second device:**

1. **Start Race → Join**.
2. Type the room code (or pick the room from the list — it is public).
3. Tap **Join race**.

Both devices now show both players. Each taps their **Ready** checkbox; the host taps
**Start race**.

You will get the placement phase — each player picks a start slot in turn — and then the race
itself. Whoever's turn it is sees a countdown in the corner: an unanswered turn is forfeited
after the room's turn limit (20 seconds unless the host changes it in the lobby's race settings), and that clock is the warning.

> **Stuck?** "No such room" usually means a typo. The code alphabet has no `0` and no `1` on
> purpose, and the server folds a typed `0` to `O` and `1` to `I`, so those two are safe to get
> wrong — but `8`/`B` and `5`/`S` are not.

### Try a private room

Host again, this time typing a password. The room still appears in the list — with a lock —
because `/rooms` publishes every code by design; the password is the security boundary, and
the listing is honest about that. Joining now asks for it, and five wrong guesses in a minute
from one address will be rate-limited.

---

## 4. Find the race you just played

Finish a race (or let the turn timer forfeit its way to the end). The server logs:

```
Logged race AB3XK (47 moves) to 2026-08-27T14-02-11Z_AB3XK.gridracer-replay
```

Look in the `races/` directory:

```bash
ls races/
# 2026-08-27T14-02-11Z_AB3XK.gridracer-replay  races.jsonl

cat races.jsonl | jq -c '{roomCode, trackName, winner: .standings[0].name, moves}'
# {"roomCode":"AB3XK","trackName":"Oval","winner":"Martin","moves":47}
```

### Watch it back

The `.gridracer-replay` file is exactly what the app's replay browser reads, so no conversion
is needed:

```bash
adb push races/2026-08-27T14-02-11Z_AB3XK.gridracer-replay /sdcard/Download/
```

On the device: **Replay Browser → Import…**, pick the file from Downloads, then **Play**.
The race plays back move for move — including any oil-slip outcomes, because every move in the
log carries the random seed the engine was given.

This is the point of race logging: a disputed result, a suspected cheat, or a bug report that
says "the board was wrong" can all be answered by watching what actually happened.

### Turning it off

If you would rather keep no record:

```bash
GRIDRACER_RACE_LOG=off ./gradlew :server:run
```

The startup line changes to `Race logging is off (GRIDRACER_RACE_LOG)` and nothing is written.
To keep logs for a different length of time, set `GRIDRACER_RACE_LOG_RETENTION_DAYS`.

> Race logs contain the display names of everyone in the race. If you run a server other people
> play on, the retention window you choose is a promise you are making to them — see the
> privacy policy in `legal/`.

---

## 5. Put it on the internet

Everything above works on your own network. To let people outside it play, you need a machine
that is always on, a domain name, and TLS.

The full production setup — systemd unit, reverse proxy with automatic certificates, firewall,
backups, upgrades — is in [README.md → Deploying on a VPS](README.md#deploying-on-a-vps). It is
a walkthrough too; work through it in order.

Three things that catch people out, in the order they will catch you:

1. **Bind to localhost, not `0.0.0.0`.** `HOST=127.0.0.1` in the systemd unit. The reverse
   proxy is the only thing that should reach the server directly; do not open port 8080 in the
   firewall.
2. **Set `GRIDRACER_TRUST_PROXY=true` — but only once a proxy is actually in front.** Without
   it, every connection appears to come from the proxy and shares one rate-limit budget, so one
   abusive client throttles everyone. With it and *no* proxy, any client can claim any address
   by sending its own `X-Forwarded-For`, which hands out free identities.
3. **Give the service write access to the race-log directory.** `ProtectSystem=strict` means
   `ReadWritePaths=` is the complete list of what the server may write to. If
   `GRIDRACER_RACE_LOG_DIR` points outside it, races are silently not logged.

Then, in the app, the address becomes:

```
wss://gridracer.example.com/play
```

`wss`, not `ws` — the proxy terminates TLS.

### Verify

```bash
curl https://gridracer.example.com/health   # OK
curl https://gridracer.example.com/rooms    # []
```

If `/health` answers but the app will not connect, the proxy is probably not forwarding the
WebSocket upgrade. Both the Caddy and nginx configurations in the README handle it; the nginx
one needs the `Upgrade`/`Connection` headers, which is the line people delete.

---

## Where to go next

- **[README.md](README.md)** — every configuration variable, the limits and abuse protections,
  the database schema, and the VPS deployment guide.
- **`shared/.../multiplayer/Protocol.kt`** — the wire protocol, message by message, with the
  reasoning for each version bump.
- **`server/src/test/`** — the server's behaviour, written down as assertions. If you want to
  know what happens when someone drops mid-race, `RoomTest` says.
