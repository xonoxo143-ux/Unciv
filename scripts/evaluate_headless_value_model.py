#!/usr/bin/env python3
"""Evaluate a trained Unciv headless value model against value-training.jsonl rows."""

from __future__ import annotations

import argparse
import csv
import json
import math
from collections import defaultdict
from pathlib import Path
from typing import Dict, Iterable, List, Sequence, Tuple


def sigmoid(value: float) -> float:
    if value >= 0:
        z = math.exp(-value)
        return 1.0 / (1.0 + z)
    z = math.exp(value)
    return z / (1.0 + z)


class TinyValueModel:
    def __init__(self, data: Dict[str, object]):
        self.features: List[str] = list(data["features"])
        norm = data["normalization"]
        weights = data["weights"]
        self.means: List[float] = [float(x) for x in norm["means"]]
        self.stds: List[float] = [float(x) or 1.0 for x in norm["stds"]]
        self.w1: List[List[float]] = [[float(x) for x in row] for row in weights["w1"]]
        self.b1: List[float] = [float(x) for x in weights["b1"]]
        self.w2: List[float] = [float(x) for x in weights["w2"]]
        self.b2: float = float(weights["b2"])

    def score(self, features: Dict[str, float]) -> float:
        x = [
            (float(features.get(name, 0.0)) - self.means[index]) / self.stds[index]
            for index, name in enumerate(self.features)
        ]
        hidden = []
        for row, bias in zip(self.w1, self.b1):
            hidden.append(math.tanh(bias + sum(weight * value for weight, value in zip(row, x))))
        return sigmoid(self.b2 + sum(weight * value for weight, value in zip(self.w2, hidden)))


def find_training_files(root: Path) -> List[Path]:
    if root.is_file():
        return [root]
    return sorted(root.rglob("value-training.jsonl"))


def load_rows(paths: Sequence[Path]) -> List[Dict[str, object]]:
    rows = []
    for path in paths:
        with path.open("r", encoding="utf-8") as handle:
            for line_no, line in enumerate(handle, 1):
                line = line.strip()
                if not line:
                    continue
                try:
                    row = json.loads(line)
                    row["sourceFile"] = str(path)
                    rows.append(row)
                except Exception as exc:  # noqa: BLE001
                    raise ValueError(f"Bad row in {path}:{line_no}: {exc}") from exc
    return rows


def evaluate(model: TinyValueModel, rows: Sequence[Dict[str, object]]) -> Tuple[List[Dict[str, object]], Dict[str, object]]:
    scored = []
    for row in rows:
        score = model.score({str(k): float(v) for k, v in row["features"].items()})
        label = float(row["label"])
        scored.append({
            "seed": int(row["seed"]),
            "turn": int(row["turn"]),
            "phase": str(row["phase"]),
            "civ": str(row["civ"]),
            "winner": row.get("winner"),
            "victoryType": row.get("victoryType"),
            "finalTurn": int(row["finalTurn"]),
            "label": label,
            "score": score,
            "absoluteError": abs(score - label),
            "correctSide": (score >= 0.5) == (label >= 0.5),
        })

    by_seed_phase: Dict[Tuple[int, str], List[Dict[str, object]]] = defaultdict(list)
    for row in scored:
        by_seed_phase[(row["seed"], row["phase"])].append(row)

    matchups = []
    for (seed, phase), group in sorted(by_seed_phase.items()):
        if len(group) < 2:
            continue
        best = max(group, key=lambda item: item["score"])
        actual_winner = group[0]["winner"]
        matchups.append({
            "seed": seed,
            "phase": phase,
            "predictedLeader": best["civ"],
            "predictedScore": best["score"],
            "actualWinner": actual_winner,
            "correctLeader": best["civ"] == actual_winner,
            "victoryType": group[0]["victoryType"],
            "finalTurn": group[0]["finalTurn"],
        })

    by_phase: Dict[str, List[Dict[str, object]]] = defaultdict(list)
    for row in matchups:
        by_phase[row["phase"]].append(row)

    phase_summary = []
    for phase, group in sorted(by_phase.items(), key=lambda item: phase_sort_key(item[0])):
        correct = sum(1 for row in group if row["correctLeader"])
        phase_summary.append({
            "phase": phase,
            "count": len(group),
            "leaderAccuracy": correct / len(group),
            "averagePredictedLeaderScore": sum(row["predictedScore"] for row in group) / len(group),
        })

    metrics = {
        "rows": len(scored),
        "matchups": len(matchups),
        "rowAccuracy": sum(1 for row in scored if row["correctSide"]) / max(1, len(scored)),
        "rowMAE": sum(row["absoluteError"] for row in scored) / max(1, len(scored)),
        "phaseSummary": phase_summary,
    }
    return matchups, metrics


def phase_sort_key(phase: str) -> Tuple[int, str]:
    if phase == "start":
        return (0, phase)
    if phase.startswith("turn-"):
        return (int(phase.removeprefix("turn-")), phase)
    if phase == "final":
        return (999999, phase)
    return (500000, phase)


def write_csv(path: Path, rows: Sequence[Dict[str, object]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    if not rows:
        path.write_text("", encoding="utf-8")
        return
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0].keys()))
        writer.writeheader()
        writer.writerows(rows)


def write_markdown(path: Path, metrics: Dict[str, object], matchups: Sequence[Dict[str, object]]) -> None:
    lines = [
        "# Headless Value Model Evaluation",
        "",
        f"- Rows evaluated: {metrics['rows']}",
        f"- Seed/phase matchups: {metrics['matchups']}",
        f"- Row accuracy: {metrics['rowAccuracy']:.3f}",
        f"- Row MAE: {metrics['rowMAE']:.3f}",
        "",
        "## Leader accuracy by phase",
        "",
        "| Phase | Count | Leader accuracy | Avg predicted leader score |",
        "|---|---:|---:|---:|",
    ]
    for row in metrics["phaseSummary"]:
        lines.append(
            f"| {row['phase']} | {row['count']} | {row['leaderAccuracy']:.3f} | {row['averagePredictedLeaderScore']:.3f} |"
        )
    lines += ["", "## First matchups", ""]
    for row in list(matchups)[:20]:
        result = "correct" if row["correctLeader"] else "wrong"
        lines.append(
            f"- Seed {row['seed']} {row['phase']}: predicted {row['predictedLeader']} "
            f"({row['predictedScore']:.3f}), actual {row['actualWinner']} — {result}"
        )
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser(description="Evaluate a trained Unciv headless value model")
    parser.add_argument("--input", default="measurement-results", help="Result directory or value-training.jsonl file")
    parser.add_argument("--model", default="measurement-results/value-model.json", help="Trained value-model.json")
    parser.add_argument("--output", default="measurement-results/value-evaluation", help="Output directory")
    args = parser.parse_args()

    model = TinyValueModel(json.loads(Path(args.model).read_text(encoding="utf-8")))
    rows = load_rows(find_training_files(Path(args.input)))
    matchups, metrics = evaluate(model, rows)

    output = Path(args.output)
    output.mkdir(parents=True, exist_ok=True)
    (output / "metrics.json").write_text(json.dumps(metrics, indent=2, sort_keys=True), encoding="utf-8")
    write_csv(output / "matchups.csv", matchups)
    write_markdown(output / "report.md", metrics, matchups)
    print(json.dumps(metrics, indent=2, sort_keys=True))
    print(f"Wrote {output}")


if __name__ == "__main__":
    main()
