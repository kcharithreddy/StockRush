# Security Policy

## Reporting a vulnerability

Please do not open a public issue for security problems. Report them privately through
GitHub: **Security → Report a vulnerability** on this repository. You should get a reply
within a few days.

## Scope

StockRush is a demonstration project. The local setup in `docker-compose.yml` is meant for a
single developer machine: every port is bound to `127.0.0.1` and all passwords come from a
git-ignored `.env` file. See the Security section of [ARCHITECTURE.md](ARCHITECTURE.md) for the
current controls and the hardening roadmap.
