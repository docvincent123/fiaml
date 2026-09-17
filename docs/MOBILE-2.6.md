# RehaFlow 2.6 — QR and mobile clinical forms

1. Update the server first; deploy the APK built from the same commit.
2. Registrar/admin: open a patient card and choose «QR-код пацієнта».
3. Android: sign in, tap the scanner icon in the header and grant camera access.
4. Scan the displayed or printed QR. Verify name and date of birth before care.

The QR is an HTTPS URL containing only the patient UUID, not their name or medical
record. It does not grant access. Native scanning accepts only the configured
server origin and an exact patient URL. Old bed labels are deliberately rejected:
they identify a location, not a person. Reissue patient QR labels after server IP
changes. A normal phone camera can open the URL in the browser; this requires
LAN reachability, a trusted CA and a browser login. Automatic opening in the
Android app from the stock camera is not implemented.

The native patient card has direct actions to prescribe for that patient and
record an examination. Fields start blank; no clinical facts are assumed.
Server permissions and approved-shift checks still apply. On an uncertain save,
inspect history before entering another record. Exam drafts are in memory only;
do not close the form or rotate during entry. Persistent drafts are future work.

React dialogs have larger touch targets, 16px fields, full-width phone layouts,
scrollable tabs and a sticky heading. Desktop uses the same React interface.

Validation: server tests and React/server build; Android CI runs compilation,
unit tests and lint. Real-device acceptance still required: denied camera access,
cancel scan, foreign QR, archived patient, revoked role, lost Wi-Fi, phone and
tablet keyboards, and scanning under low light. No face recognition is used.

Scanner integration: https://github.com/journeyapps/zxing-android-embedded
