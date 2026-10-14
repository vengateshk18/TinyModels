# Security Policy

## Supported versions

TinyModels is pre-1.0 and developed on the `worker` branch. Security fixes go
into the latest code — please test against the newest commit before reporting.

| Version | Supported |
| ------- | --------- |
| latest `worker` / `main` | ✅ |
| older commits | ❌ (please update first) |

## Reporting a vulnerability

**Do not open a public issue for security problems.** Email details and
reproduction steps to **vengatesh00014@gamil.com**:

- What the issue is and where (file/feature, app version or commit).
- Steps to reproduce, and what an attacker could gain.
- Your contact for follow-up questions.

You will get an acknowledgment, and we will coordinate a fix before any public
disclosure. Our detailed practices are documented at
https://vengateshk18.github.io/TinyModels/security.html.

## Scope notes

- The app is fully on-device: there is no server side to probe.
- In scope: token handling, file import/export, local database, deep links,
  notifications, and any bundled dependency with a known CVE.
- Out of scope: the Hugging Face website itself, third-party model files'
  behavior, and physical device access.
