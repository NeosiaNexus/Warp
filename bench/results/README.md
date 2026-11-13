# Committed results

Only a smoke run is committed: one directory, all five proxies, as proof that each adapter and every
scenario runs end to end. It was produced on a rented 16-core server with the smoke profile (a
handful of bots, windows of a few seconds) and makes no performance claim: the report says so in its
first lines. A real run (`bench/run.sh` without `--smoke`, on tier T1 or T2 hardware, see
`docs/benchmarks/README.md`) is published separately.

The directory holds the report (`README.md`), every measurement with its validity verdict
(`metrics/`), the per-second samples (`csv/`), the exact config each proxy ran with (`configs/`),
the sizes, CPU plan and thresholds of the run (`run.json`) and the host fingerprint (`env.json`,
hostname redacted). The working directories and logs of a run are not committed. The report can be
rebuilt from these files with `bench/run.sh --report-only bench/results/<date>`.
