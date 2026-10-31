# Security policy

## Reporting a vulnerability

Please **do not open a public issue**. Report privately through
[GitHub security advisories](../../security/advisories/new) (Security tab → "Report a vulnerability").

Include what you found, how to reproduce it, and the version. Expect a first answer within a few days.

## Scope and threat model

unitship runs every app as the same Unix user (see "Limits" in the README): apps can read each other's files.
Reports that need to run untrusted code on a host are out of scope. In scope: the CLI, the ssh/git flow, the panel
(authentication, sessions, CSRF, file upload), certificate handling and anything that exposes secrets in `config`.

Only the latest release gets security fixes.
