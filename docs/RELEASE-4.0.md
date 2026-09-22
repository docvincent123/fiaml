# RehaFlow 4.0.0 — reliability update

## Implemented

- Local encrypted drafts for registration, appointments, prescriptions and clinical records on Android and desktop. Reopen the same form, under the same user and server, to recover it. Ordinary drafts expire after seven days when accessed; unresolved desktop submissions and Android's pending operation are retained until resolved. Successful saves remove their draft. Drafts are not shared between devices. Clearing browser/app data or reinstalling can destroy them. Unused expired encrypted records are removed on access, not by a background erasure scheduler.
- Desktop: AES-GCM ciphertext in localStorage with non-extractable WebCrypto keys in IndexedDB. Android: AES-GCM with Android Keystore; Android cloud backup is disabled. These protect stored draft contents but do not protect a compromised logged-in device or malicious same-origin code. Staff should lock shared workstations.
- Durable server receipts for registration, readmission, clinical entries and appointments; prescriptions retain their existing receipt mechanism. Actor, operation identity and payload are checked, and the result and receipt commit in one transaction. Concurrent retries produce one record; changing a committed payload with the same ID returns 409. This does not make unrelated uploads, handovers or administrative writes automatically replay-safe.
- Desktop freezes an uncertain submission and restores it after reload with the same operation ID. Retry sends it again for server confirmation. Android retains the exact pending JSON payload and offers explicit retry after reconnect/relogin. No automatic offline clinical-write queue. Existing draft contents must be reviewed, especially dates, patient identity and admission. Clinical drafts carry the original admission ID; fixed-patient prescriptions also check it.
- Administrator chooses an 8, 12 or 24-hour shift when approving it (API validates 1–24 hours). Sessions expire after a bounded 30 hours from login; there is no indefinite silent extension. Existing sessions/shifts keep their original expiry, so log in again for a new 4.0 shift. Desktop displays the shift end and warns in its final 30 minutes; Android shows the shift/session end in settings.
- Windows boot autostart uses Task Scheduler with S4U, under the installing Windows account, without interactive sign-in. This is not a Windows Service. It preserves the existing local configuration and Caddy CA; it has no network-share credentials. API and HTTPS process failures are supervised; persistent health failure exits and Task Scheduler attempts at most three restarts. Daily backup also runs without an interactive login. The Windows account must remain enabled and local task execution must be permitted by center policy.
- Administrator sees the last successful notification poll for active nurses/therapists. This is contact health, not proof a sound played or a person read a task. The first restored notification feed no longer silently drops outstanding events. Notifications exclude course repetitions beyond the current shift.
- Optional Android “Режим поста” keeps the screen awake while an approved shift is open and the app remains visible, intended for a plugged-in center tablet. It does not defeat the lock button, force-stop, Do Not Disturb or Android background restrictions. Foreground activity stops repeating task alerts as before.
- Promoting staff to administrator now requires a newly supplied password of at least 12 characters. Existing staff password policy is preserved.

## Upgrade

1. Make and verify a backup; preserve `.env.nodocker`, `.local/caddy-data`, attached data and the existing certificate.
2. On Windows run `scripts/Stop-Server.ps1` as administrator. Older manually started servers may need their window closed; confirm ports 3000/443 are free.
3. Install the 4.0 server package, then run the existing installer/setup. It builds the server, preserves configuration and registers the boot task. Migration 007 adds a notification heartbeat column without rewriting medical records.
4. Install the 4.0 clients after the server. Log in again to obtain the new session lifetime. Do not clear old installation data as an update procedure.
5. To start the newly registered task immediately after closing a manual server: `Start-ScheduledTask -TaskName 'RehaFlow Server'`.
6. Verify boot with nobody logged into Windows, then verify browser/phone HTTPS using the same CA. A changed LAN address still requires updating client addresses. Do not bypass certificate validation.

## Release limits and checks still required

The APK remains a CI test build, not a production-signed distribution. Android 15 target SDK 35 applies a six-hour dataSync background service budget per 24-hour period (returning to the app resets the budget). Long unattended background delivery is not guaranteed. Reference: https://developer.android.com/develop/background-work/services/fgs/timeout

Before clinical rollout: test a full actual shift, screen lock and network transitions, notification channels and QR on physical phones; a Windows cold boot without login and logout while serving clients; recovery from actual backup on a spare PC; 30–35 simultaneous clients. Compile/test results do not establish these operational guarantees. Off-machine backup policy and production signing remain separate launch tasks.
