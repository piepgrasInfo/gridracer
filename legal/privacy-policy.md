# Privacy Policy — Grid Racer

**Effective date: 2026-10-02**

**Published at:** https://piepgras.info/legal/grid-racer/privacy-policy/

## Who we are

Grid Racer is developed by Martin Kruse ("we", "us", "our"), operator of the
website https://piepgras.info. You can reach us via apps@piepgras.info.
Our postal address is listed at https://piepgras.info/imprint/.

## Overview

Grid Racer has two modes:

- **Local play** — single-player, hot-seat, and AI races. Everything here
  stays on your device.
- **Online play** — multiplayer races, accounts, a server-side rating
  leaderboard, and a shared custom-track library. These features connect to
  our server at `gridracer.piepgras.info` over an encrypted connection and
  transmit the data described below. You can also point the app at a server
  someone else runs, such as one on your own home network; see *Servers you
  choose yourself*.

You can use the app entirely in local mode without ever creating an account
or connecting to the server. Online features are opt-in: the app does not
connect to the server until you enter the multiplayer race setup screen,
and accounts are created only if you choose to register.

## What Grid Racer stores on your device

The following data is written to files in the app's private storage on your
device and never transmitted anywhere:

- **Settings** — your preferences (player name, server URL, sound toggle,
  crash-reporting toggle, language, placement order).
- **Local leaderboard** — race results from local games.
- **Custom tracks** — tracks you design in the in-app track editor.
- **Replays** — recordings of your finished local races (up to approximately
  20, retained newest-first).

Uninstalling the app removes all of this data from the device.

## What Grid Racer sends to the server

When you use online features, the app connects to our multiplayer server at
`gridracer.piepgras.info` over an encrypted WebSocket connection (see
*Encryption in transit* below) and sends the following:

### Account data

If you create an account, the app sends:

- **Username** — the name you choose. Must be unique. Stored on the server. **Your username
  is public**: it is shown to other players next to any track you upload (see *Custom track
  uploads*), so choose one you are comfortable being seen.
- **Password** — sent when you register, log in, or delete your account,
  over the encrypted connection. The server hashes it with PBKDF2 and never
  stores it in plaintext. Only the hash is kept on the server.
- **Display name** — the name shown to other players in multiplayer lobbies
  and on the leaderboard. Defaults to your username if you don't set one.

You do not need an account to play multiplayer as a guest, but accounts are
required to upload shared tracks and to appear on the rating leaderboard.

### Session token

After registration or login, the server issues a session token that the app
stores on your device and presents on future connections so you don't have
to re-enter your password. The token is valid for 90 days. Logging out
invalidates it immediately.

A token belongs to the server that issued it. If you change the server address
in Settings, the app discards the stored token rather than presenting it to
the other server, and you log in again when you switch back.

### Multiplayer game data

During online races, the app sends:

- **Player name** — shown to other players in the lobby.
- **Game moves** — the target points you choose for your turns. These are
  validated by the authoritative server and broadcast to other players in
  the same room.
- **Room interactions** — joining, leaving, ready status, and (for hosts)
  track selection, lap count, seat count, turn timeout, and race start.

### Custom track uploads

When you upload a custom track to share with other players, the app sends:

- **Track name** — the name you gave the track in the editor.
- **Track geometry** — the walls, boundaries, obstacles, and finish line
  that define the track. The server rebuilds the playable track from your
  design data; it does not accept a pre-compiled track, so a crafted track
  whose geometry disagrees with its design cannot be uploaded.

Uploaded tracks are public: any other player can browse, download, and play
them. **Both your username and your display name** are recorded as the author
and shown to other players, alongside the name of the server the track lives
on. Uploading is the point at which your username becomes visible to people
you have not played against.

### Track ratings

If you rate a shared track, the app sends:

- **The track's public ID.**
- **Your score** (1 to 5).
- **Your account ID** (rating requires an account).

One rating is kept per account per track; rating a track again replaces your
previous score. Other players see only the *average* score and the number of
ratings — never who gave which score. You can see your own score on the track's
info screen.

### Track reports

If you report a shared track for inappropriate content, the app sends:

- **The track's public ID.**
- **The reason you give** (free text).
- **Your account ID** (reports require an account).

### Encryption in transit

From version 1.3, the app reaches our server over TLS
(`wss://gridracer.piepgras.info/play`), the same encryption HTTPS uses, and
checks the server's certificate before it sends anything. Everything listed in
this section, your password and session token included, is encrypted between
your device and our server. The encrypted connection ends at a web server
(Caddy) running on the same machine as the game server, which passes it on
locally; nothing in between can read it.

Versions before 1.3 connected without encryption, on port 5521. We keep that
port open so those versions go on working until they are updated, and what
they send, including the password at registration, login and account
deletion, crosses the network unencrypted. Updating the app moves you to the
encrypted connection; you do not need to change anything in Settings.

## Servers you choose yourself

Settings lets you replace our server's address with another one, for example
a Grid Racer server you run on your own home network. Such a server is run by
whoever operates it, not by us, and this policy does not cover what it does
with your data. The app sends it the same kinds of data listed above.

Encryption is optional on such a server:

- An address beginning with `wss://` is encrypted, and the app checks the
  server's certificate exactly as it does for ours.
- An address beginning with `ws://` is **not encrypted**. Your password,
  session token and game data cross the network in plain text, readable by
  anyone on the network between you and that server. While such an address is
  set, the app warns you in the Server section of Settings, above the login
  form, and again in the account-deletion dialog. Use one only on a network
  you trust.

The app shows a padlock beside the connection status only once an encrypted
connection is established, and an open padlock for an unencrypted one.

## What the server stores

The server keeps a SQLite database with the following tables:

- **Accounts** — username (unique), password hash, display name, Elo rating,
  match count, win count, and creation date.
- **Sessions** — session tokens with a 90-day expiry, linked to accounts.
- **Shared tracks** — track name, author account ID, author display name,
  track design data, and upload timestamp.
- **Track ratings** — track ID, rater account ID, score (1–5), and timestamp.
  One row per account per track.
- **Track reports** — reported track ID, reporter account ID, reason, and
  timestamp. Used for manual review of reported content.

The server also keeps active game state (rooms, players, current race
positions) in memory during active games. This is not persisted to disk and
is discarded when a room closes.

### Race logs

**This is new. Please read it even if you have read this policy before.**

When an online race finishes, the server writes a record of it to a file so
the race can be replayed afterwards. A race log contains:

- the room code and the track that was raced;
- the display name of each player, and whether that seat was a human, a guest
  or an AI opponent;
- for players with accounts, the account's public ID — the same opaque
  identifier used by the leaderboard. **Never** your password, your session
  token, or the server's internal database ID for your account;
- every move made in the race, with the time it was made;
- the final standings.

**Why we keep it.** A race that nobody recorded cannot be settled afterwards.
Race logs let us investigate a disputed result, look into suspected cheating
or rating manipulation, and diagnose a bug report that says "the game showed
me the wrong thing" — none of which is possible from the leaderboard totals
alone.

**How long.** Race logs are deleted automatically after 30 days by default.
The operator of a server can change this window, or turn race logging off
entirely, through the server's configuration. On our server at
`gridracer.piepgras.info` the retention period is 30 days.

**Who sees them.** Nobody outside the provider. Race logs are not published,
not listed anywhere in the app, and not shared with third parties. A player
can ask for a copy of a race they took part in.

**Local play is never logged.** Races you play on your own device never reach
the server at all.

**Legal basis (GDPR Art. 6(1)(f)):** our legitimate interest in operating a
fair multiplayer service that can be debugged and whose results can be
verified. If you object to your races being logged, you can play locally, or
play on a server whose operator has disabled logging.

### Server logs

The server logs IP addresses in its application log for rate limiting and
abuse prevention. Your address is used to bound how much traffic, how many
login attempts, and how many simultaneous connections can come from one
place — a limit that applies per *address* rather than per connection,
because a limit per connection is bypassed by opening another one. This means
your IP address is held in memory for as long as those limits are being
counted (minutes), and appears in the application log when a limit is hit.

These logs are rotated as part of normal server operation, are not stored in
the database, and are not linked to your account.

## Elo rating leaderboard

When you finish a multiplayer race with an account, the server updates your
Elo rating based on the finishing order of all account-holding players in
that race. The public leaderboard shows:

- Display name
- Rating (a number, starting at 1000)
- Matches played
- Wins

Guest players and AI players are not rated and do not appear on the
leaderboard.

## Crash reporting

Crash reporting is off by default. If you turn it on in Settings, the app
sends a crash report to Bugsink, our self-hosted error-tracking service, at
piepgras.bugsink.com, whenever it crashes or hits an unexpected error. A
report contains:

- the error message and stack trace,
- your device model and OS version,
- the app's version.

It does not include your name, email, account credentials, or anything
you've typed or created in the app. The Sentry SDK is configured to strip
user identifiers, server names, screenshots, view hierarchies, and
interaction breadcrumbs before sending. As with any network request, the
report does expose your device's IP address to that server. Crash reports
are transmitted over an encrypted HTTPS connection. You can turn crash
reporting back off at any time in Settings; until you turn it on, no crash
reports are sent anywhere.

- **Server location:** the Netherlands. The data therefore stays within the
  European Union, and no transfer to a third country takes place.
- **Retention:** crash reports are kept no longer than needed to diagnose the
  fault, and at most one year, after which they are deleted.
- **Recipients:** nobody outside the provider. The instance is self-hosted; the
  reports are not passed to an analytics provider or advertiser.
- **Legal basis (GDPR Art. 6(1)(a)):** your consent, given by turning crash reporting on.

## Notifications

If you are in an online multiplayer race and background the app while it is
your turn, Grid Racer can show a local notification reminding you before the
room's turn timer forfeits your move. On Android 13 and newer this requires
the notification permission, requested once on first launch — see
[permissions.md](permissions.md) for what happens if you decline it.

This notification is generated entirely on your device from the race state
the app already holds because it is connected to the room. No additional
data is collected or sent anywhere to produce it, it is not logged, and
nothing about it is retained once it has been shown or dismissed.

## Third-party software

The following third-party software components are involved in data
transmission or storage:

- **Sentry Android SDK** (`io.sentry:sentry-android-core`) — used to send
  crash reports to the provider's self-hosted Bugsink instance. The SDK is
  included in the app; the data it sends goes to the provider's own server,
  not to Sentry's hosted service. The Sentry SDK's privacy practices are
  described in the Sentry Privacy Policy: https://sentry.io/privacy/
- **Ktor** (`io.ktor`) — the framework running the multiplayer server and
  the client's WebSocket connection, including its encryption. Ktor does not
  collect or transmit any data of its own.
- **Caddy** — the web server in front of the multiplayer server, on the same
  machine. It holds the server's TLS certificate, decrypts the connection and
  passes it to the game server. It does not collect or transmit any data of
  its own.
- **SQLite** (`org.xerial:sqlite-jdbc`) — the database engine used by the
  server for persistent storage. SQLite does not collect or transmit any
  data of its own.

No advertising networks, analytics providers, or attribution SDKs are
integrated into the app or the server.

## Your rights under the GDPR

You have the right to access, rectify, erase, restrict, and port your
personal data, and to object to its processing — contact us at
apps@piepgras.info.

For account data, you can erase everything yourself in the app under
**Settings → Delete account…**; we can also identify you by your username and
delete your account, sessions, uploaded tracks, track ratings and rating
history on request, and remove your name from any race log naming you. For
crash reports, we generally cannot tell which reports came from you; tell
us what you can (e.g. roughly when it happened) and we'll help however that
makes possible.

You always have the right to lodge a complaint with your local data
protection supervisory authority if you believe your rights have been
violated.

We do not perform automated decision-making or profiling.

### Legal basis for processing

- **Account data and multiplayer game data (GDPR Art. 6(1)(b)):** necessary
  to provide the online multiplayer service you requested.
- **Shared tracks and track reports (GDPR Art. 6(1)(b) / Art. 6(1)(f)):**
  necessary to provide the track-sharing feature, and our legitimate
  interest in reviewing reported content.
- **Elo ratings and leaderboard (GDPR Art. 6(1)(b) / Art. 6(1)(f)):**
  necessary to provide the rating service, and our legitimate interest in
  operating a competitive leaderboard.
- **Track ratings (GDPR Art. 6(1)(b)):** necessary to provide the rating
  feature you asked for.
- **Race logs (GDPR Art. 6(1)(f)):** our legitimate interest in operating a
  fair multiplayer service whose results can be verified and whose faults can
  be diagnosed. See *Race logs* above.
- **Server logs and rate limiting (GDPR Art. 6(1)(f)):** our legitimate
  interest in protecting the service from abuse.
- **Crash reports (GDPR Art. 6(1)(a)):** your consent, given by turning
  crash reporting on.

## Device backups

The app allows Android's standard backup. If backup is enabled in your device
settings, a copy of the app's local data — including your settings, local
leaderboard, custom tracks, and replays — may be stored in your Google
account under Google's terms. Server-side data (accounts, shared tracks,
ratings) is not part of this backup. You can prevent local-data backup in
your device's backup settings, and remove existing copies there.

## Data retention and deletion

- **Local data on your device:** kept until you delete it. Uninstalling the
  app removes all local data from the device.
- **Account data:** kept until you delete the account. You can delete it
  yourself in the app under **Settings → Delete account…**, which removes your
  account, sessions, uploaded tracks, track ratings and rating history
  immediately, or write to apps@piepgras.info and we will do it for you. Your
  name and account handle are removed from stored race logs at the same time;
  the races themselves are kept for the other drivers and expire on their own
  within 30 days. Full instructions:
  https://piepgras.info/legal/grid-racer/delete-account/
- **Session tokens:** expire automatically after 90 days of inactivity, and
  are deleted on logout.
- **Shared tracks:** kept until you delete them or until we remove them
  following a report review. Deleting your account also deletes your
  uploaded tracks.
- **Track ratings:** kept until you delete your account, or until the track
  is removed.
- **Track reports:** kept until the reported track is reviewed and acted
  upon, then deleted.
- **Race logs:** deleted automatically after 30 days on our server, index
  included. Deleting your account removes your name and account handle from
  the logs kept in the meantime. Ask us if you want a copy of a race you
  played in, or want one removed sooner.
- **Server logs:** rotated as part of normal server operation; not kept
  indefinitely.
- **Device backups:** clear the app's backup data in your operating system's
  backup settings.
- **Crash reports:** see above. To have reports relating to your device
  deleted, write to apps@piepgras.info.

## Children's privacy

Grid Racer is a game and not directed at children under 13 (or the age of
digital consent in your country). The app does not knowingly collect personal
data from children. Account registration requires a username and password
but does not collect age information; if you believe a child under the
applicable age has created an account, contact us and we will delete it.

## Permissions

The app declares the following permissions. A plain-language explanation of
each is in [permissions.md](permissions.md).

| Permission | Why |
| --- | --- |
| Internet (`INTERNET`) | Used for multiplayer connectivity, account login, track sharing, and crash reporting (when enabled). Local play does not require this permission. |
| Notifications (`POST_NOTIFICATIONS`) | Used to remind you it's your turn in an online race if you background the app. Requires your approval on Android 13+; see [permissions.md](permissions.md). |

The app requests no access to your location, camera, microphone, contacts,
calendar, call history, SMS or files outside its own storage.

## Changes to this policy

If Grid Racer's data practices change, this policy will be updated to
describe them before the change takes effect, and the effective date above
revised.

## Contact

Contact: apps@piepgras.info
