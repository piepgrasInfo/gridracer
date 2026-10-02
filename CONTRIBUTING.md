# Contributing to Grid Racer

Thanks for looking. Issues and pull requests are welcome.

## Licensing of contributions

Grid Racer is licensed under the **MIT License**. Inbound contributions are
accepted under the same licence — what you send is what everyone downstream
receives, on the same terms. There is no copyright assignment and no CLA to sign.

Sign off each commit with the [Developer Certificate of
Origin](https://developercertificate.org/), which is the assertion that you
wrote the patch or otherwise have the right to submit it under the MIT License:

```bash
git commit -s -m "..."
```

That appends a `Signed-off-by:` line. Please don't paste in code whose licence
you can't account for. Nothing can be merged that the MIT licence cannot carry:
no GPL, LGPL or AGPL code (it would impose copyleft on everything downstream),
and nothing under proprietary or non-commercial terms.

## Security reports

Please **don't** open a public issue for a vulnerability, especially one in the
server's account handling, session tokens or rate limiting. See
[SECURITY.md](SECURITY.md).

## Getting set up

```bash
./gradlew :shared:jvmTest           # engine unit tests - fast, run after any shared/ change
./gradlew :server:test              # server tests
./gradlew :app:testDebugUnitTest    # app unit tests
./gradlew :app:assembleDebug        # build the APK
./gradlew :app:lintDebug            # Android lint
```

You need JDK 17+ (the Gradle daemon toolchain requests JDK 25) and the Android
SDK for API 37, with its path in `local.properties`.

## What a good change looks like

- **The engine stays platform-free.** `shared/` has no Android imports: it is
  compiled for the JVM as well, it is where the rules live, and it is what the
  server and the client must agree on. A rule change that only happens in
  `app/` is a desync waiting for a multiplayer race.
- **Rule changes come with engine tests.** `shared/src/commonTest/` is the
  cheapest place in the project to prove something, and the suite is fast
  enough to run on every change.
- **Netcode changes keep the client and server in step.** The wire format is
  shared; changing one side alone breaks races between versions rather than
  failing a build.
- **Lint stays at zero errors.** `./gradlew :app:lintDebug` — warnings are
  tolerated, errors are not.
- **User-facing strings go in `res/values/strings.xml`**, never inline. German
  and Russian translations are welcome but not required; untranslated strings
  fall back to English.

## Pull requests

Describe what the change does and why, and say what you ran. A PR that
changes the rules engine, the wire protocol or the database schema should say
so explicitly in its description — those are the three places where a mistake
is expensive rather than merely wrong.

Maintainer review can take a while: this is a spare-time project.
