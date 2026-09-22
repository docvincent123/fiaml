# RehaFlow: workspace UI and next priorities

## Delivered

- Light desktop workspace with grouped navigation, restrained teal/slate colors, clear dashboard metrics and low-cost motion. Eco and reduced-motion settings still disable animation; print styling is not overridden.
- Patient search in the top bar (Ctrl/Cmd+K), including URL-driven search and card navigation. Patient table shows patient number and birth date.
- Daily desktop appointment filter with native date input, today shortcut and direct patient-card access.
- Desktop patient card actions for examination and prescription with the selected patient fixed in the prescription form. Examination history is now editable on both desktop and Android.
- Android shift-task search across server results by patient name, number, room and description; overdue task labels. Search retains role/shift filtering and pagination.
- Android allergy panel near the top of the card shows the latest assessment for the current admission, author and time. If it has corrections, the panel directs staff to read them. Missing data is explicitly unknown, never interpreted as no allergy.

## Next work worth doing

1. **Verify full-shift notification delivery on actual devices.** Current local polling has Android background limits. Test locked screens, Wi-Fi changes and 12/24-hour shifts; decide whether clinic-managed tablets or an internet-backed push channel is appropriate before promising uninterrupted delivery.
2. **Structured mobile observations.** Desktop has numeric pulse, blood pressure, SpO2, temperature, glucose and pain fields; the native observation form is still mostly free text. Reuse server validation and show trends with recorded time/author; do not invent clinical thresholds or automatic treatment advice.
3. **Rehabilitation progress and agreed scales.** Goals and results are recorded, but structured scales, baseline/follow-up comparison and course-level progress would help therapists. Select the center's actual scales and workflow first.
4. **Interruption recovery.** Prescription retry deduplication exists. Extend proven idempotent saves to other forms, add drafts with explicit unsaved state and local retention controls. Offline clinical writes need conflict handling, not just a queue.
5. **Restore drills and capacity checks.** Backup/restore scripts and status already exist. Test restoring to a spare PC, representative 35-device traffic and the center's actual Wi-Fi coverage before rollout.

## Verification

UI review uses only synthetic records with the production React bundle. It checks desktop/laptop/mobile web widths, search, patient selection in a prescription, schedule-to-card navigation and browser exceptions. Native Android still needs device review. Existing SQL integration tests, Android compile/unit tests/lint and Windows packaging remain required.
