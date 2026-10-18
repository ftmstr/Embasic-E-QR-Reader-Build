# Public build security policy

- Never commit real scanned payloads, history databases, captures, screenshots from users, API tokens, APK signatures, signing keystores, `local.properties`, `google-services.json`, or production advertisement IDs.
- Never transfer the private repository's commits, tags, branches or Git objects. Only independently audited source text belongs here.
- Android permissions should remain limited to what the implemented workflow needs; image picker should use Android's system picker.
- Payload parsing and local risk evaluation stay offline; never auto-open URLs, Wi-Fi, phone, SMS, or payment actions.
- Read-only workflow token; no production secrets in public CI.
- All reports are evidence based; no claim of a working APK before success in Actions.
- Public git author email metadata is not filtered by source-code secret scans; ensure GitHub account commits use a noreply identity before future public writes.
- Review the merged manifest, dependency licenses, third-party SDK network behavior, CVEs, user consent, and camera tests before any release.
