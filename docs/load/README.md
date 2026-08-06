# Load profile

The login check is the only endpoint on a critical path — every player connection waits on it — so
that is what the profile hammers. Moderator actions run alongside at a realistic trickle, because a
write path measured in isolation tells you nothing about how it behaves next to the read load that
actually exists.

## Running it

```bash
docker compose up --build -d
k6 run -e TOKEN=dev-local-token -e HOST=http://localhost:8080 docs/load/punishments.js
```

Ramps to 800 lookups/s over five minutes against a pool of 10 000 UUIDs, with 5 issue+lift cycles
per second in parallel. The UUID pool is larger than the cache, so a meaningful share of lookups
miss and reach PostgreSQL — a profile that only ever hits the cache measures Caffeine, not the
service.

## What to watch

- `active_lookup_ms` p99 — the number that matters; a login is waiting on it.
- `http_req_failed` — anything above zero under this load is a defect, not saturation.
- `proxypunishments_cache_lookups_total{result="hit"}` vs `miss` on `/actuator/prometheus` — the hit
  ratio explains the latency distribution.
- `proxypunishments_repository_calls_seconds` — separates database time from framework overhead.
- Postgres `pg_stat_statements` — if the alt query or the active lookup shows a sequential scan,
  an index is missing.

## Results

Not filled in. These numbers are worthless unless they come from a real run on known hardware, and
a benchmark table invented by whoever wrote the script is worse than no table at all.

| Metric | Value | Hardware |
|---|---|---|
| Lookup p50 | | |
| Lookup p99 | | |
| Sustained lookups/s | | |
| Cache hit ratio | | |
| Error rate | | |

Record the machine, the JVM, the PostgreSQL version and whether the service and database shared a
host — a p99 from a laptop running both in Docker is a different measurement from a p99 across a
network.
