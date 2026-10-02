# Permissions — Grid Racer

**Effective date: 2026-10-02**

**Published at:** https://piepgras.info/legal/grid-racer/permissions/

Every permission the installed app holds, why it is there, and what happens
if you refuse it.

## Granted at install

### Internet — `INTERNET`

**What it is for:** connecting to the Grid Racer multiplayer server
(`gridracer.piepgras.info`) for online races, account login, browsing and
sharing custom tracks, and viewing the rating leaderboard. It is also used
to send crash reports to Bugsink, our error-tracking service, when you have
turned on crash reporting in Settings (crash reporting is off by default).

**How the connection is protected:** the connection to our server and the
crash reports are encrypted (TLS). A server you set yourself in Settings, such
as one on your own home network, may be unencrypted (an address beginning with
`ws://`); the app then warns you in Settings that what you send it, your
password included, is readable on the network.

**When it is used:** the app connects to the server only when you enter the
multiplayer race setup screen or the account/track-sharing screens. Local
play (single-player, hot-seat, AI races) does not require this permission,
and with crash reporting off, the app makes no network requests during local
play.

**What it does not do:** it grants no access to your camera, microphone,
location, contacts, or storage outside its own app folder.

### `info.piepgras.gridracer.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`

**What it is:** a permission the Android build tools generate for the app and
grant only to the app itself, so that receivers registered at runtime cannot be
triggered by other apps.

**What it is for:** internal safety. It grants no access to anything and is not
visible to you or to other apps as a capability.

## Requested at runtime

### Notifications — `POST_NOTIFICATIONS`

**What it is for:** letting the app show a "your turn" notification when you
are in an online multiplayer race, the app is in the background, and the
server is waiting on your move. Online races are turn-based with a per-room
time limit set by the room's host; a backgrounded player who never sees the
clock running loses their turn to it. Tapping the notification reopens the
app to the race.

**When it is requested:** once, the first time you open the app on Android 13
or newer. Devices on older Android versions grant this automatically and are
never prompted.

**What happens if you refuse it, or turn it off later in system settings:**
the app works exactly the same in every other respect. You simply will not
get a reminder if you background the app during your turn, and the room's
timeout still applies regardless — the notification only helps you notice it
in time, it does not change or extend it.

**What it does not do:** generating this notification involves no new data
collection. The app already knows whose turn it is and how much time is left
from the race it is already connected to; nothing about it is sent to us or
to anyone else to produce the notification, and nothing about the
notification itself is stored once it is shown.

## Never requested

The app does not request, and cannot access:

- location, in any form;
- camera or microphone;
- contacts, calendar or call history;
- SMS or phone state;
- broad file/storage access;
- exact alarms or wake locks;
- foreground-service permissions;
- the advertising identifier — there is no advertising and no tracking.

Only one permission requires your explicit approval — notifications, and only
on Android 13 and newer (see above). Every other permission listed here is
granted automatically at install and never prompts you.

## If this changes

If a future version of Grid Racer needs another permission, this page will be
updated to list exactly what was added and why, matching the app's
declared permissions at that time.
