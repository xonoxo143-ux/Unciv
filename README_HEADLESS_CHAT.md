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

## Output

The runner writes:

- `summary.csv`
- `games.jsonl`
- `crashes.jsonl`
- `report.md`

This is the first bridge artifact for letting ChatGPT run real seeded Unciv simulations after the GitHub Action builds the jar.
