# Terms of Service — Grid Racer

**Effective date: 2026-10-02**

**Published at:** https://piepgras.info/legal/grid-racer/terms-of-service/

## 1. About these terms

These terms cover the service — the app as we distribute it, and the online
features we operate. They are **not** the licence to the software: Grid Racer
is open-source software, licensed to you under the MIT License, and Section 4
says what that means. Grid Racer publishes no separate End User License
Agreement; the MIT License already grants more than one would.

## 2. Acceptance

By installing or using Grid Racer, you agree to these terms. If you don't
agree, please don't use the app.

## 3. What Grid Racer is

Grid Racer is a turn-based vector-racing game played on a grid, developed by
Martin Kruse ("we", "us", "our"), operator of https://piepgras.info. It
includes local play (single-player, hot-seat, AI opponents) and online
multiplayer features (real-time races, accounts, a rating leaderboard, and
a shared custom-track library).

## 4. The software licence

**Grid Racer is open-source software** under the **MIT License**. The source
code, with the full licence text, is at:

> https://github.com/piepgrasInfo/gridracer

The MIT License lets you use the app for any purpose, including commercially;
study how it works; change it; and copy, publish, distribute, sublicense or
sell it, changed or not. Its one condition is that the copyright notice and
the licence text go with every substantial copy. You may decompile and
reverse-engineer the app — though with the source published, there is little
left to reverse-engineer.

Nothing in these terms limits the rights the MIT License grants you in the
software; where these terms and the licence disagree about what you may do
with the software, the licence wins. What these terms do govern is the
*service* — the servers we operate, the accounts on them and the content
uploaded to them — which a software licence does not cover, and which
Sections 5 to 8 describe.

The MIT License is a copyright licence and grants no trademark rights. The
name "Grid Racer" and the app's icon and store artwork, as the identity of this
app, are not licensed for branding another build. You may publish a fork;
please publish it under its own name and icon, so that nobody installs it
believing it to be this one.

### The build you are running

The copies we publish on Google Play are built from that source with one
addition that is not in it: an endpoint for the opt-in crash reporting
described in the Privacy Policy. A build you make yourself from the published
source has no such endpoint and reports nothing anywhere.

## 5. Accounts

Online features include optional user accounts. If you create an account:

- You are responsible for keeping your password secure and for all activity
  under your account.
- You must provide a valid username and password. We do not collect email
  addresses or other contact information at registration.
- Session tokens are issued for convenience and are valid for 90 days. You
  can log out to invalidate a token immediately.
- We may suspend or delete accounts that violate these terms, particularly
  Section 8 (Acceptable use).

You can play multiplayer as a guest without an account, but uploading shared
tracks and appearing on the rating leaderboard require an account.

From version 1.3 the app reaches our server over an encrypted connection, so
your password is encrypted on its way to us (the Privacy Policy, *Encryption
in transit*, has the details). The app also lets you connect to a server we do
not operate, such as one on your own home network. These terms do not cover
such a server or the accounts on it. Encryption is optional there: on a server
whose address begins with `ws://`, your password is sent unencrypted, and the
app warns you about it in Settings before you log in.

## 6. Your custom tracks

Tracks you design in the in-app track editor are yours. We don't claim
ownership over them.

Tracks you upload to the shared track library are published publicly to
other Grid Racer players. **Both your username and your display name are
published with the track** as its author. By uploading a track, you grant us
a non-exclusive, royalty-free license to host, store, transmit, and display
that track — and your authorship of it — to other users for the purpose of
operating the track-sharing feature. You can delete your uploaded tracks at
any time; deleting your account also deletes all tracks you have uploaded.

You are responsible for the content of tracks you upload. Do not upload
tracks that contain content you do not have the right to share, or that
violate Section 8 (Acceptable use).

Other players can report shared tracks they believe are inappropriate, using
the **Report this track** action on the track's info screen. We review
reported tracks manually and may remove tracks that violate these terms.

Players with accounts can also rate shared tracks from 1 to 5. Ratings are
aggregated: other players see the average and the number of ratings, not who
gave what. We may discount or remove ratings we believe were manipulated.

## 7. Elo rating leaderboard

When you finish a multiplayer race with an account, your rating is updated
based on the finishing order. Only matchmade races count; a private room you
set up with friends is not a rated contest. The leaderboard is public and
shows display names, ratings, match counts, and win counts.

We reserve the right to reset or adjust ratings in cases of suspected
manipulation or cheating. Finished races are recorded so that such a claim
can actually be checked rather than guessed at — see *Race logs* in the
[Privacy Policy](privacy-policy.md) for what a recording contains and how
long it is kept.

## 8. Acceptable use

You agree not to use Grid Racer to:

- violate applicable law;
- cheat, manipulate game state, or exploit bugs in the multiplayer server;
- disrupt multiplayer races, lobbies, or the server itself (including
  spamming, flooding, or excessive room creation);
- upload tracks that contain illegal, infringing, hateful, harassing, or
  sexually explicit content;
- attempt to access, modify, or delete other users' data without
  authorization;
- use automated tools (bots, scripts) to interact with the server, except
  for the app's own normal operation.

We may restrict, suspend, or terminate access to online features for
violations of these rules. Local play remains available regardless.

### Automated limits

Some rules are enforced automatically, without a person looking at it first,
so that one client cannot spoil a race or a server for everybody else:

- **Turn timeout.** If it is your turn in an online race and you do not move
  before the room's time limit runs out, your turn is forfeited and the race
  continues without you. The host sets this limit for the room (5, 10 or 20
  seconds; 20 unless they change it) from the lobby before the race starts.
  The remaining time is shown on screen to everyone in the room, not just
  whoever is moving, while a turn is running.
- **Placement timeout.** If you do not choose a starting slot within 45
  seconds, one is chosen for you.
- **Idle rooms.** A room where nothing happens is closed — after 30 minutes
  in the lobby, or 15 minutes during a race.
- **Rate limits.** Requests are limited per connection and per network
  address. Exceeding a limit drops the request, not the connection; you will
  see a "slow down" message.

None of these are penalties, and none of them affect your account: they exist
because everyone else in the room is waiting.

## 9. No warranty

Grid Racer is provided "as is," without warranty of any kind, express or
implied, to the maximum extent permitted by applicable law. We don't
guarantee the app will be uninterrupted, error-free, or fit for a
particular purpose. The multiplayer server is operated on a best-effort
basis and may be unavailable at any time without notice. We do not
guarantee that server-side data (accounts, ratings, shared tracks, race
recordings) will be preserved indefinitely. Race recordings in particular are
deleted on a schedule; see the [Privacy Policy](privacy-policy.md).

## 10. Limitation of liability

To the maximum extent permitted by applicable law, the provider is not liable
for indirect, incidental, or consequential damages arising from your use
of Grid Racer. Nothing in these terms excludes or limits liability that
cannot be excluded or limited under applicable law — in particular liability
for intent or gross negligence, for injury to life, body or health, or
arising from a guarantee expressly given. For consumers, mandatory statutory
rights remain unaffected.

## 11. Termination

You may stop using Grid Racer at any time by uninstalling it. You may delete
your account at any time in the app under **Settings → Delete account…**, or
by contacting us at apps@piepgras.info. Deletion is immediate and permanent;
see https://piepgras.info/legal/grid-racer/delete-account/ for what it
removes.
We may discontinue online features or the multiplayer server at any time
without prior notice.

## 12. Conformity (EU consumers)

If you are a consumer in the EU, Grid Racer must conform to the description
provided in these terms and on its Google Play listing, as required by EU
Directive 2019/770 on digital content and services. If the app does not
conform, you may request that the provider bring it into conformity. Because
the app is provided free of charge, the primary remedy is to bring the app
into conformity or, if that is not possible, to discontinue use. These
remedies are in addition to, and do not affect, your statutory consumer
rights.

## 13. Governing law

These terms are governed by the law of the Federal Republic of Germany,
excluding its conflict-of-laws rules and the UN Convention on Contracts for the
International Sale of Goods. If you are a consumer, this choice of law does
not deprive you of the protection of mandatory provisions of the law of the
country in which you have your habitual residence.

## 14. Changes to these terms

If Grid Racer's features change, these terms will be updated to match and
the effective date above revised.

## 15. Contact

Contact: apps@piepgras.info
