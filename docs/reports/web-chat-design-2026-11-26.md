# Web chat design acceptance — 2026-11-26

Implemented in the existing Mango web client, without schema changes:

- Compact incoming/outgoing bubbles, day separators and time-only metadata. A single check means accepted by the server, not read by the recipient.
- Accessible message action disclosure: reply, copy, reaction and create task. Open menus survive incoming history updates.
- Replies use the existing `replyToId` API and render the server's `replyTo.textSnapshot`. Retry keeps the same message and reply IDs.
- Image albums, original image viewer and download. Other attachments keep individual download controls.
- Voice play/pause, seek, duration and waveform decoded from the actual audio. Audio that cannot be decoded keeps the playback/seek fallback. Waveform decoding is limited to 20 MiB.
- Expandable transcription calls the existing authorized server endpoint only after pressing “Показать текст”. Errors allow retry. Server speech configuration and availability are required.
- Attachment, emoji and microphone controls; send replaces the microphone when text or an attachment is ready.

Validation:

- `scripts/test-web-ui.cjs`: passed desktop/mobile layout, reply payload and quote rendering, image album/viewer, actual PCM audio playback/seek/duration, escaped transcription rendering (mock speech response), retry and multiple-file upload, existing task/admin flows.
- `scripts/test-chat-live.cjs`: passed on local Docker with two browser sessions: real reply quote survives re-login, WebSocket delivery/reconnect, offline catch-up, token renewal, multi-file downloads and member permissions.
- `:app:installDist --offline`: passed. Deployed `mango-backend:chat-design-20261126`; readiness succeeded. Previous image retained as `mango-backend:before-chat-design-20261126`.
- Live speech recognition accuracy and Android UI were not tested in this change.

Screenshots: `artifacts/web-acceptance/chat-desktop-fixture.png`, `artifacts/web-acceptance/chat-mobile-fixture.png`, `artifacts/chat-live-acceptance/chat-live.png`.
