# Unciv Headless Chat Pack

This package is for running no-UI Unciv simulations from a plain JVM environment.

## Run

```bash
java -jar UncivHeadless.jar --config measurement-config.json
```

Optional command-line overrides:

```bash
java -jar UncivHeadless.jar --games 20 --max-turns 500 --seed-start 42017 --output measurement-results
```

For chat-safe one-seed runs:

```bash
java -jar UncivHeadless.jar --seed 42017 --max-turns 500 --per-game-timeout-seconds 120 --output measurement-results/seed-42017
```

## Output behavior

The runner now writes after each seed instead of waiting for the whole batch to finish. If a later seed stalls or crashes, completed seed rows should already be present on disk.

The runner writes:

- `summary.csv`
- `games.jsonl`
- `crashes.jsonl`
- `report.md`

`summary.csv` includes a `status` column. Possible statuses are:

- `completed`
- `draw`
- `crash`
- `timeout`

A per-game watchdog exits the process with code `124` on timeout after writing a timeout row and refreshing the report.

This is the bridge artifact for letting ChatGPT run real seeded Unciv simulations after the GitHub Action builds the jar.
