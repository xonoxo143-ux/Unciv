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

Evaluate whether the model is picking the eventual winner at each checkpoint:

```bash
python3 evaluate_headless_value_model.py --input measurement-results --model measurement-results/value-model.json --output measurement-results/value-evaluation
```

The evaluator writes:

- `value-evaluation/report.md`
- `value-evaluation/matchups.csv`
- `value-evaluation/metrics.json`

## Neural construction reranking

The runtime can now load a trained `value-model.json` and use it as a bounded reranker for city construction decisions. It does not replace the original AI. It nudges the existing construction score up or down by at most 25%.

Enable it with either a JVM property:

```bash
java -Dunciv.neural.valueModel=measurement-results/value-model.json -jar UncivHeadless.jar --games 20 --output measurement-results/neural-construction
```

or an environment variable:

```bash
UNCIV_NEURAL_VALUE_MODEL=measurement-results/value-model.json java -jar UncivHeadless.jar --games 20 --output measurement-results/neural-construction
```

Optional strength override:

```bash
java -Dunciv.neural.valueModel=measurement-results/value-model.json -Dunciv.neural.constructionStrength=1.0 -jar UncivHeadless.jar --games 20 --output measurement-results/neural-construction
```

This is the first control path: real games -> value model -> neural-assisted city construction. It still needs paired baseline-vs-neural measurement before it should be considered better than the built-in AI.

This is the bridge artifact for letting ChatGPT run real seeded Unciv simulations after the GitHub Action builds the jar.
