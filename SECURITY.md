# Security Policy

## Supported Versions

Helix-Cloud is **pre-1.0, Alpha software**. There is no long-term support
branch and no backporting of fixes to older tags — only the latest released
version is supported. Expect breaking changes between minor versions until a
1.0 release ships.

## Reporting a Vulnerability

Please do **not** open a public GitHub issue for security vulnerabilities.

Instead, report it privately by emailing **Lordytwhite@gmail.com** (the
project maintainer, as listed in [NOTICE](NOTICE)) with:

- A description of the vulnerability and its potential impact.
- Steps to reproduce it (a minimal Task/Service configuration, request, or
  addon that triggers the issue helps a lot).
- The Helix-Cloud version (and any relevant addon versions) you tested
  against.

You should get an acknowledgement within a few days. Since this project is
maintained on a best-effort basis, there is no formal SLA for a fix, but
confirmed vulnerabilities will be prioritized over regular feature work and
credited in the fix's changelog entry unless you ask to stay anonymous.

## Scope

In scope: the `helix-node` orchestrator, the `helix-wrapper`, the Paper and
Velocity bridges, the bundled `helix-addon-*` modules, and the
`helix-dashboard` web panel — all as shipped in this repository.

Out of scope: vulnerabilities in third-party dependencies (report those
upstream), and issues that require an attacker to already have Launcher
admin-token or host-level access to the machine running the node.

## Known accepted risks

- **A compromised game server can issue network bans.** IGuard (the
  anticheat shipped with `helix-addon-guard`) intentionally bans from the
  game server through the node's `guard.store.ban`/`unban`/`punishment`
  actions, which are reachable with that server's per-service token
  (`bridgeInvocable`). An attacker with code execution on a game server
  could therefore ban or unban arbitrary players network-wide. This is an
  accepted trade-off: the anticheat's automatic enforcement requires it,
  every such action is written to the audit log with its acting service,
  and bans are reversible from the dashboard. Mitigation if unacceptable
  for your deployment: disable IGuard's automatic bans and use alerts with
  manual panel bans instead.
