# widgetbridge-smart-wakeup (SleepWake)

Smart wake-up with a CMF Watch Pro 2 and a Gadgetbridge fork, plus a small webapp that stores and graphs the sleep data.
Full specification: [reference-docs/SLEEPWAKE_PROJECT_SPEC.md](reference-docs/SLEEPWAKE_PROJECT_SPEC.md).

| Part | Folder | Status |
|---|---|---|
| Webapp (Laravel + Blade + MySQL) | [webapp/](webapp/README.md) | done, live at <https://widget.peppasoft.com> |
| Android app (Gadgetbridge fork, module `sleepwake`) | `android/` (not started) | waiting for Phase 0 (`DISCOVERY.md`) and review |

## Webapp

See [webapp/README.md](webapp/README.md): setup, API, cleanup (auto-delete after N days, default 90, for data and for logs), deploy.

Deploy: `bash deploy/deploy.sh` (ger1, user `widget`).

## Android

The spec says Phase 0 (reading the Gadgetbridge code and the real watch data) must be written to `DISCOVERY.md` and reviewed
before any implementation. That is the next step.
