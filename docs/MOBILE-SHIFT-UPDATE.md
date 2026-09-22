# RehaFlow 3.0 — mobile shift update (Android build 11)

Install the new APK together with the updated server. Back up the database first; preserve server configuration and TLS certificates.

- The mobile task list uses the active shift interval, including shifts crossing midnight. It includes overdue open work, assigned in-progress work and work completed during this shift. Future course repetitions beyond the shift stay out of this list.
- Global patient/task archives are restricted to administrators on the server. The native Android archive route and archive navigation are removed, including for administrators. Existing clinical history in an active patient's chart remains available to authorized clinicians.
- Only active nurses and therapists receive task ringtones. The server returns explicit ringtone eligibility; results and clinical messages use a separate silent channel. A doctor receiving a task result does not ring merely because the destination is `/tasks`.
- New background task notifications repeat the supplied ringtone until the user opens RehaFlow or dismisses the notification. Opening the app by either the icon or notification cancels the task notification. Other messages use a different notification ID and do not replace an ongoing task alert. The first feed snapshot establishes a baseline without ringing for old work.
- Android uses a visible LAN polling foreground service, every 15 seconds while permitted by the OS. It is not cloud push. The phone needs access to the clinic server. Notification permission, channel volume, Do Not Disturb, battery restrictions, force-stop and network loss can prevent sound/delivery.
- Android 15+ limits background `dataSync` foreground services to six hours per 24 hours; returning to the app resets that allowance. Timeout stops the service and leaves an informational notification; returning to the app restarts it. This implementation cannot promise unattended all-day delivery. Reference: https://developer.android.com/develop/background-work/services/fgs/timeout
- The native patient card shows the complete stored name, patient number, birth date, phone, home address, emergency contact, admission complaint and sex. It offers prescriptions, examinations (including history), observations and rehabilitation records according to permissions. Treatment shows author, scheduled time, medicine details, executor and outcome.
- Patient QR scanning inside RehaFlow opens this same native card after server-origin validation and authorization. Old bed QR codes and external camera/browser behavior are separate from patient-card QR scanning.
- “Мої пацієнти сьогодні” requests the local day's start/end instants and the signed-in specialist. The server independently restricts nurses and therapists to their assigned appointments. The list includes time, cabinet, status and a native patient-card action.

## Verification

Server tests cover shift boundaries, overdue/future/completed work, archive access, ringtone eligibility, specialist/day boundaries and patient clinical data. Android compilation, unit tests and lint run in the existing GitHub workflow.

Still check on real phones before rollout:

1. Nurse and therapist: begin an approved shift, minimize/lock the phone, create a new task of each role. Only the intended role should ring. Open through the app icon, then repeat through the notification; both should stop the sound.
2. Doctor: receive a completed task and a clinical message. Both must remain silent. A repeated feed must not ring again for the same event.
3. Deny notifications, disable the task channel, use Do Not Disturb, disconnect/reconnect Wi-Fi, revoke the session and end the shift. Confirm the visible service state and no misleading delivery claim.
4. Scan the same patient QR as doctor, nurse and therapist. Check the same identity/address, role-appropriate actions and no WebView transition. A QR from another server must be rejected.
5. Add an examination and prescription on phone/tablet with keyboard open; verify persisted author and content from another device.
6. Book two specialists today plus one appointment tomorrow. Each therapist should see only today's own list. Check a shift spanning midnight and verify the global archive is visible only in the administrator desktop interface.
