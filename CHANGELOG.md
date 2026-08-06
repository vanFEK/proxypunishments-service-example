# Changelog

Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/);
versioning follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.0.0]

### Added

- Punishment storage and lookup over a small HTTP API: bans, mutes, IP bans and warnings, with one
  domain model and one port behind them.
- Two interchangeable sources of truth chosen by one environment variable: a local PostgreSQL schema
  or an existing HTTP backend. The plugin cannot tell the difference.
- Alt detection: accounts last seen from the same address, ordered by recency.
- Liquibase migrations, a partial unique index enforcing "at most one active punishment per player
  per kind" in the database rather than in application code, and a sweeper that retires expired rows.
- Caching keyed by player and kind, invalidated on write and — optionally — across replicas over
  Redis pub/sub.
- Constant-time service-token authentication; only the health probes are public.
- OpenAPI documentation, Micrometer metrics under `proxypunishments.`, and optional Discord
  notifications.

### Notes

- Reactive end to end, WebFlux and R2DBC; the JDBC driver is present only because Liquibase needs it.
