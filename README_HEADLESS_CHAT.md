# Unciv Headless Chat Pack

This package is for running no-UI Unciv simulations from a plain JVM environment.

## Run

```bash
java -jar UncivHeadless.jar --config measurement-config.json
```

Optional command-line overrides:

```bash
java -jar UncivHeadless.jar --games 20 --max-turns 500 --seed-start 42017 --per-game-timeout-seconds 120 --output measurement-results
```

For one-seed runs:

```bash
java -jar UncivHeadless.jar --seed 42017 --max-turns 500 --per-game-timeout-seconds 120 --output measurement-results/seed-42017
```

## Process model

Normal mode is now a parent/child runner:

- The parent JVM does not load Unciv game state.
- The parent starts one fresh child JVM per seed.
- The parent enforces the per-seed timeout from outside the game process.
- The parent merges each child result into the root output files.

This avoids stale global/transient Unciv state accumulating across many games in the same JVM.

Child mode is an internal implementation detail, but it can be useful for direct development:

```bash
java -jar UncivHeadless.jar --child --seed 42017 --max-turns 500 --output measurement-results/child-42017
```

## Output behavior

The parent writes after each seed instead of waiting for the whole batch to finish. If a later seed stalls or crashes, completed seed rows should already be present on disk.

The runner writes:

- `summary.csv`
- `games.jsonl`
- `crashes.jsonl`
- `report.md`
- `seeds/<seed>/console.log`
- `seeds/<seed>/command.txt`
- `seeds/<seed>/value-training.jsonl`

`summary.csv` includes a `status` column. Possible statuses are:

- `completed`
- `draw`
- `crash`
- `timeout`

If a seed times out, the parent kills the child process, writes a timeout row, refreshes the report, and exits the overall run with code `124`.

## First learning loop

Each completed child seed writes `value-training.jsonl`. Every row is a civ snapshot from a real simulated game, labeled after the game finishes:

- `1.0` means this civ eventually won.
- `0.0` means this civ eventually lost.
- `0.5` means the game was unresolved or drawn.

Train the bundled dependency-free neural value model with:

```bash
python3 train_headless_value_model.py --input measurement-results --output measurement-results/value-model.json --epochs 300 --hidden 16 --verbose
```

This produces a tiny MLP value model that predicts eventual win probability from checkpoint features. It is not yet controlling the AI; it is the first real game-learning artifact: real games -> labeled states -> trained model.

This is the bridge artifact for letting ChatGPT run real seeded Unciv simulations after the GitHub Action builds the jar.
