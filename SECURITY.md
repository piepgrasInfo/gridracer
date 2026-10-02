# Security policy

## Reporting a vulnerability

Please report security problems privately to **apps@piepgras.info** rather than
opening a public issue. Include enough to reproduce it — a request sequence, a
patch, or a short proof of concept is ideal.

You should get an acknowledgement within about a week. This is a spare-time
project with no security team behind it, so please be patient with the timeline
for a fix; you will be told what is happening either way. There is no bug
bounty.

If you would like credit in the release notes, say so and name how you want to
be credited.

## What is in scope

The parts worth looking at hardest are in the server, which is the only
component that accepts input from strangers:

- **Account handling** — `server/src/main/kotlin/.../db/AuthService.kt`,
  `AccountDao.kt`, `Pbkdf2PasswordHasher.kt`, and session-token issuance and
  expiry.
- **The WebSocket message router** — `MessageRouter.kt` and `Validation.kt`:
  anything that lets a client act as another player, act out of turn, or drive
  the room state into an illegal position.
- **Rate limiting and connection accounting** — `security/RateLimiter.kt`,
  `Limits.kt`, `ConnectionRegistry.kt`, `ClientIp.kt`, including
  `GRIDRACER_TRUST_PROXY` handling of `X-Forwarded-For`.
- **The shared track library** — `TrackShareService.kt`: track payloads are
  attacker-controlled data that other players' devices then parse and render.
- **Race logging** — `log/RaceLogger.kt`, which writes display names to disk
  and has a retention window the privacy policy commits to.

In the app: anything that would let a crafted track, replay or server response
execute code, escape the app sandbox, or exfiltrate data from the device.

## What is out of scope

- Findings that need physical access to an unlocked device.
- Missing hardening that has no demonstrable impact — a report should say what
  an attacker can actually do.
- Denial of service against a server you run yourself.
- The crash-reporting endpoint: it is a write-only identifier, it is not in
  this repository, and source builds have crash reporting compiled out.
- Social engineering, and anything requiring a malicious Play Store listing.

## Running your own server

If you host Grid Racer for other people, the security of that deployment is
yours. Two settings are worth naming: set `GRIDRACER_TRUST_PROXY=true` **only**
when the server really is behind a reverse proxy that sets `X-Forwarded-For` —
otherwise clients can spoof their own IP and defeat rate limiting — and keep
`GRIDRACER_RACE_LOG_RETENTION_DAYS` at a value you can justify to your players,
because race logs contain display names.
