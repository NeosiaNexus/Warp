# Committed results

Only smoke runs are committed, one directory per proxy, as proof that each adapter runs end to end.
They were produced on a WSL2 development machine and make no performance claim: each report says so
in its first lines. A real run (`bench/run.sh` without `--smoke`, on tier T1 or T2 hardware, see
`docs/benchmarks/README.md`) is published separately.

Each directory holds the report (`README.md`), the per-second samples (`csv/`), the exact config the
proxy ran with (`configs/`) and the host fingerprint (`env.json`, hostname redacted). The working
directories and logs of a run are not committed.
