# AGENTS.md

## Project Rules

- Always preserve project architecture.
- Never create duplicated auth flow.
- Follow the existing folder structure.
- Update tests if behavior changes.
- Do not remove backward compatibility unless requested.
- Prefer minimal diff.
- Run formatter after editing when the project provides one.
- If migration is needed, generate migration.

## Documentation Storage Policy

- Do not create or store project documentation in a repo `docs/` folder.
- Store project documentation under `/home/apollo/Project_detail/{project name}/`.
- Only add repo pointers to external documentation when necessary.

## ElderPTOD Product Boundary

- `family/admin` is a responsive web experience for caregivers/admins.
- The elder device is Android native. Elder-device UI must be implemented in this Android app.
- Do not replace `family/admin` RWD web with Android UI.
- Do not implement elder-device screens as web-style dashboard pages.

## Android Elder UI Rules

- Treat every screen in this repo as an elder-facing mobile app screen unless clearly marked otherwise.
- Use mobile-native interaction patterns: app bar, large touch targets, bottom actions, call-screen layout, readable status states.
- Home actions must remain one row with two card buttons, not two stacked tab/list buttons.
- The Android home screen must expose font size controls at the bottom. The controls must actually scale elder-facing text, not just open a separate settings page.
- Do not create a separate sound settings screen when the only setting is speaker mode; expose `擴音` as a visible switch row on the home screen and during calls.
- If text does not fit, dynamically adjust the layout: reduce padding, allow wrapping, autosize text, or increase height. Do not let text overlap, clip, or disappear.
- Call states must use mobile call UI, including incoming, connecting, in-call, ended, missed, rejected, and failed states.
- Call result screens must remain visible long enough for an elder to read before returning home automatically.
- Incoming call actions must use large circular phone buttons: reject on the left, accept/connect on the right. Do not use stacked full-width web/form buttons for answering calls.
- When a screen asks the elder to choose an explicit yes/no or want/do-not-want action, use green for the affirmative action and red for the negative/reject action. Red should consistently mean reject, decline, stop, or do not want.
- Completed reminders must not reappear as the next reminder.
- If there is no next reminder, show `無`.
- Already paired devices should open directly to the home screen and start connecting; do not stop on a `設定完成` screen.
- After a successful new pairing, show a green flashing `配對成功` state, then auto-start after 2 seconds if the user does not press start.
- Android real devices must not use `127.0.0.1` or `localhost` as the backend URL. Default real-device testing to the HTTPS tunnel `https://elderweb.classtutorbot.com`; preserve explicit LAN URLs only when manually entered for local debugging.
- When the backend URL cannot connect, show a visible error message; never fail silently.
