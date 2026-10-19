# gadgetbridge-smart-wakeup (SleepWake)

Smart wake-up with a CMF Watch Pro 2 and a Gadgetbridge fork, plus a small webapp that stores and graphs the sleep data.
Full specification: [reference-docs/SLEEPWAKE_PROJECT_SPEC.md](reference-docs/SLEEPWAKE_PROJECT_SPEC.md).

| Part | Folder | Status |
|---|---|---|
| Webapp (Laravel + Blade + MySQL) | [webapp/](webapp/README.md) | done, live at <https://gadget.peppasoft.com> |
| Android app (Gadgetbridge fork, module `sleepwake`) | [android/](android/SLEEPWAKE.md) | built, 31 unit tests pass, installs and starts. Needs real nights on the watch (see the manual checklist) |

## Webapp

See [webapp/README.md](webapp/README.md): setup, API, cleanup (auto-delete after N days, default 90, for data and for logs), deploy.

Deploy: `bash deploy/deploy.sh` (ger1, user `gadget`).

## Android

- What the watch and the code really do: [DISCOVERY.md](DISCOVERY.md) (Phase 0).
- Setup, use, sync and the manual test checklist: [android/SLEEPWAKE.md](android/SLEEPWAKE.md).
- What was changed in upstream Gadgetbridge (4 small edits, rest is a separate build flavor `sleepwake`): [android/SLEEPWAKE_CHANGES.md](android/SLEEPWAKE_CHANGES.md).

Known limits (from Phase 0): the CMF driver cannot stop find-watch from the phone (the app repeats it until you tap "I'm awake"), heart rate has only OFF/SMART modes, there is no live data (fetch loop only), and the optional backup alarm on the watch is not implemented because it is not verified to be vibrate-only.
