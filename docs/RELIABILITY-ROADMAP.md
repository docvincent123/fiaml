# Reliability work: implementation checkpoint

## Implemented in this checkpoint

Prescription creation accepts an optional UUID `request_id`. Updated React and
native Android prescription forms generate one per form instance. Repeating the
same normalized payload with that ID returns the original result. Changing its
payload returns HTTP 409. Separate authors have separate request namespaces.

The receipt and all course tasks commit in the same PostgreSQL transaction.
The user-row lock serializes competing retries; a failed transaction leaves no
receipt. Receipts survive API restart. Existing authorization and shift checks
still apply. Migration 006 adds a receipt table without changing patient records.

Deploy the server before the clients. Back up the database before updating.
Older clients remain compatible, but requests without a UUID are not deduplicated.
This is NOT a generic offline queue: closing a form and opening a new one creates
a new operation. After an uncertain save, check the active course/archive before
closing the form or entering another course. Do not delete receipt rows casually.

Verification: local server tests and server/React production builds. Android
compilation and real-phone retry/rotation scenarios require separate checks.

## Remaining scope (not delivered by this checkpoint)

- Windows background service before login, restricted service identity,
  bounded health recovery, preservation of existing CA and configuration.
- Extend existing per-user scheduled maintenance (already has daily backups)
  with alternate-media copies, retention and real isolated restore tests.
- Discharge draft, medical review and explicit authorized head approval;
  final immutable versions and deliberate handling of unfinished tasks.
- Device pairing with proof of possession, approval and revocation; a displayed
  installation UUID alone is not device authentication.
- Structured rehabilitation goals, assessments and outcome comparison.
- QR pairing and secure server-address discovery without bypassing TLS.
- Extend durable request deduplication to registration, entries and bookings;
  persistent encrypted drafts need their own privacy and conflict design.
- Load and failure testing with 30–35 clients; Windows boot/logout and Android
  background behavior must be tested on actual devices.

UPS runtime and safe-shutdown thresholds depend on actual hardware/load. Do not
start a long emergency backup on a nearly depleted battery: orderly shutdown
takes priority. BIOS power-return behavior cannot be enabled by an app guarantee.
