# Changelog

## 0.1.0 - 2026-10-30

First release: read-side SDKs for Python (3.9+), Node.js (18+) and Java (17+).

- `runtime(project)` fetches every secret of a project across all groups; `secret(project, key)` fetches one value.
- `projects()`, `groups(project)` and `secretNames(project[, group])` return metadata only.
- `health()` reports the server version.
- Errors expose the HTTP status and the stable OneKey error code (`AUTHORIZATION_DENIED`, `PROJECT_NOT_FOUND`, ...).
- Secret containers never print values in `repr`, `inspect` or `toString`.
