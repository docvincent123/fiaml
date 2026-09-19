# RehaFlow 3.0.0 — mobile acceptance checks

The launcher now routes to Compose screens exclusively. No launcher navigation
opens MainActivity, a browser, or WebView. Existing WebView code remains for
compatibility but is not used by the native navigation.

## Delivered
- Native patients, task creation/results/archive, clinical assessments,
  observations, rehabilitation notes, messages, rooms, appointments, patient
  handovers and team handovers.
- Native registration, password change, staff/session/audit lists and
  administrator shift approval.
- Mobile login sends requestShift=false. Each non-admin must request a shift
  explicitly. The workspace is unavailable until the server confirms an active
  shift; existing active shifts resume. Administrators bypass the gate.
- Android notification channel uses the system notification sound and vibration.
  Settings include a test notification and Android channel settings.
- Version 3.0.0 in APK, server, React, Windows assembly and installer.

## Current limits
- This is not full desktop feature parity: advanced staff/permission editing,
  room/bed administration, patient document uploads, discharge document
  generation and bed allocation remain desktop workflows.
- The LAN server must be reachable. Internet is not needed on the centre Wi-Fi,
  but this does not add an offline medical database.
- Alerts poll every 15 seconds while the foreground service is permitted.
  Android notification permissions, muted channels, DND, force-stop, battery
  restrictions and the Android data-sync service time limit can prevent sound
  or background delivery. There is no guarantee of always-on push.

## Before using with real patients
1. Install the new APK over the matching test package and update the server.
2. For every staff role: login, choose No and verify no workspace; login again,
   choose Yes, verify waiting state, approve from admin, verify access.
3. Close/reopen during an active shift; verify resume. End/reject/expire shift;
   verify workspace locks again. Admin must not get the shift gate.
4. Open every menu and a patient from tasks/messages/schedule/QR; verify native
   content and correct patient identity, back navigation, phone/tablet layouts.
5. Create one nurse and one therapist task; check each intended device sounds
   and opens Tasks. Re-poll/reopen and verify no repeated old notification.
6. Test foreground, background, locked screen, denied notification permission,
   DND, loss/recovery of Wi-Fi, and an expired/revoked session.
7. Validate registration, procedure booking conflicts, assessment/observation/
   rehab saving and author attribution against server records.
