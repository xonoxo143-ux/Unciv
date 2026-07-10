#!/usr/bin/env python3
"""
Train a tiny neural value model from Unciv headless value-training.jsonl files.

Input records are emitted by the headless runner under:
  measurement-results/seeds/<seed>/value-training.jsonl

The target is a simple win-probability value label:
  1.0 = this civ eventually won
  0.0 = this civ eventually lost
  0.5 = draw/unresolved

This is intentionally dependency-free so it can run in a plain Python install.
It is not meant to be the final AI. It is the first real learning loop:
real games -> labeled states -> trained model artifact.
"""

from __future__ import annotations

import argparse
import json
import math
import os
import random
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, Iterable, List, Sequence, Tuple


@dataclass
class Example:
    features: Dict[str, float]
    label: float


def find_training_files(root: Path) -> List[Path]:
    if root.is_file():
        return [root]
    return sorted(root.rglob("value-training.jsonl"))


def load_examples(paths: Sequence[Path]) -> List[Example]:
    examples: List[Example] = []
    for path in paths:
        with path.open("r", encoding="utf-8") as handle:
            for line_no, line in enumerate(handle, 1):
                line = line.strip()
                if not line:
                    continue
                try:
                    row = json.loads(line)
                    features = {str(k): float(v) for k, v in row["features"].items()}
                    label = float(row["label"])
                except Exception as exc:  # noqa: BLE001 - include file context for bad data
                    raise ValueError(f"Bad row in {path}:{line_no}: {exc}") from exc
                examples.append(Example(features=features, label=label))
    return examples


def feature_space(examples: Sequence[Example]) -> List[str]:
    keys = set()
    for example in examples:
        keys.update(example.features.keys())
    return sorted(keys)


def normalize(examples: Sequence[Example], features: Sequence[str]) -> Tuple[List[List[float]], List[float], List[float], List[float]]:
    raw = [[example.features.get(name, 0.0) for name in features] for example in examples]
    labels = [example.label for example in examples]
    if not raw:
        return [], [], [], []

    means: List[float] = []
    stds: List[float] = []
    for column in zip(*raw):
        mean = sum(column) / len(column)
        variance = sum((value - mean) ** 2 for value in column) / max(1, len(column) - 1)
        std = math.sqrt(variance) or 1.0
        means.append(mean)
        stds.append(std)

    normalized = [
        [(value - means[index]) / stds[index] for index, value in enumerate(row)]
        for row in raw
    ]
    return normalized, labels, means, stds


def sigmoid(value: float) -> float:
    if value >= 0:
        z = math.exp(-value)
        return 1.0 / (1.0 + z)
    z = math.exp(value)
    return z / (1.0 + z)


class TinyValueNet:
    def __init__(self, inputs: int, hidden: int, rng: random.Random):
        scale1 = 1.0 / math.sqrt(max(1, inputs))
        scale2 = 1.0 / math.sqrt(max(1, hidden))
        self.w1 = [[rng.uniform(-scale1, scale1) for _ in range(inputs)] for _ in range(hidden)]
        self.b1 = [0.0 for _ in range(hidden)]
        self.w2 = [rng.uniform(-scale2, scale2) for _ in range(hidden)]
        self.b2 = 0.0

    def forward(self, x: Sequence[float]) -> Tuple[List[float], float]:
        hidden_values = []
        for row, bias in zip(self.w1, self.b1):
            total = bias + sum(weight * value for weight, value in zip(row, x))
            hidden_values.append(math.tanh(total))
        logit = self.b2 + sum(weight * value for weight, value in zip(self.w2, hidden_values))
        return hidden_values, sigmoid(logit)

    def train_one(self, x: Sequence[float], y: float, learning_rate: float, l2: float) -> float:
        hidden_values, prediction = self.forward(x)
        error = prediction - y
        loss = -(y * math.log(max(prediction, 1e-9)) + (1.0 - y) * math.log(max(1.0 - prediction, 1e-9)))

        old_w2 = self.w2[:]
        for i, hidden in enumerate(hidden_values):
            grad = error * hidden + l2 * self.w2[i]
            self.w2[i] -= learning_rate * grad
        self.b2 -= learning_rate * error

        for i, hidden in enumerate(hidden_values):
            grad_hidden_pre = error * old_w2[i] * (1.0 - hidden * hidden)
            for j, value in enumerate(x):
                grad = grad_hidden_pre * value + l2 * self.w1[i][j]
                self.w1[i][j] -= learning_rate * grad
            self.b1[i] -= learning_rate * grad_hidden_pre

        return loss

    def predict(self, x: Sequence[float]) -> float:
        return self.forward(x)[1]

    def to_json(self, features: Sequence[str], means: Sequence[float], stds: Sequence[float], metrics: Dict[str, float]) -> Dict[str, object]:
        return {
            "modelType": "tiny_mlp_value_v1",
            "target": "eventual_win_probability",
            "features": list(features),
            "normalization": {
                "means": list(means),
                "stds": list(stds),
            },
            "hiddenActivation": "tanh",
            "outputActivation": "sigmoid",
            "weights": {
                "w1": self.w1,
                "b1": self.b1,
                "w2": self.w2,
                "b2": self.b2,
            },
            "metrics": metrics,
        }


def split_data(xs: List[List[float]], ys: List[float], validation_fraction: float, rng: random.Random):
    indices = list(range(len(xs)))
    rng.shuffle(indices)
    validation_count = int(len(indices) * validation_fraction)
    validation = indices[:validation_count]
    train = indices[validation_count:] or indices
    return train, validation


def evaluate(model: TinyValueNet, xs: Sequence[Sequence[float]], ys: Sequence[float], indices: Sequence[int]) -> Dict[str, float]:
    if not indices:
        return {"count": 0, "loss": float("nan"), "accuracy": float("nan"), "mae": float("nan")}
    losses = []
    correct = 0
    abs_errors = []
    for index in indices:
        y = ys[index]
        pred = model.predict(xs[index])
        losses.append(-(y * math.log(max(pred, 1e-9)) + (1.0 - y) * math.log(max(1.0 - pred, 1e-9))))
        abs_errors.append(abs(pred - y))
        if (pred >= 0.5) == (y >= 0.5):
            correct += 1
    return {
        "count": len(indices),
        "loss": sum(losses) / len(losses),
        "accuracy": correct / len(indices),
        "mae": sum(abs_errors) / len(abs_errors),
    }


def train(args: argparse.Namespace) -> Dict[str, object]:
    rng = random.Random(args.seed)
    files = find_training_files(Path(args.input))
    examples = load_examples(files)
    if len(examples) < 4:
        raise SystemExit(f"Need at least 4 examples, found {len(examples)} from {len(files)} file(s).")

    features = feature_space(examples)
    xs, ys, means, stds = normalize(examples, features)
    train_indices, validation_indices = split_data(xs, ys, args.validation_fraction, rng)
    model = TinyValueNet(inputs=len(features), hidden=args.hidden, rng=rng)

    for epoch in range(args.epochs):
        rng.shuffle(train_indices)
        total_loss = 0.0
        for index in train_indices:
            total_loss += model.train_one(xs[index], ys[index], args.learning_rate, args.l2)
        if args.verbose and (epoch == 0 or (epoch + 1) % max(1, args.epochs // 10) == 0):
            train_metrics = evaluate(model, xs, ys, train_indices)
            valid_metrics = evaluate(model, xs, ys, validation_indices)
            print(
                f"epoch={epoch + 1} train_loss={train_metrics['loss']:.4f} "
                f"train_acc={train_metrics['accuracy']:.3f} valid_loss={valid_metrics['loss']:.4f} "
                f"valid_acc={valid_metrics['accuracy']:.3f}"
            )

    metrics = {
        "examples": len(examples),
        "files": len(files),
        "features": len(features),
        "train": evaluate(model, xs, ys, train_indices),
        "validation": evaluate(model, xs, ys, validation_indices),
    }
    return model.to_json(features, means, stds, metrics)


def main() -> None:
    parser = argparse.ArgumentParser(description="Train a tiny Unciv headless value model")
    parser.add_argument("--input", default="measurement-results", help="Result directory or value-training.jsonl file")
    parser.add_argument("--output", default="measurement-results/value-model.json", help="Model JSON output path")
    parser.add_argument("--hidden", type=int, default=16, help="Hidden layer width")
    parser.add_argument("--epochs", type=int, default=300, help="Training epochs")
    parser.add_argument("--learning-rate", type=float, default=0.03, help="SGD learning rate")
    parser.add_argument("--l2", type=float, default=0.0001, help="L2 weight decay")
    parser.add_argument("--validation-fraction", type=float, default=0.2, help="Holdout fraction")
    parser.add_argument("--seed", type=int, default=1337, help="Trainer RNG seed")
    parser.add_argument("--verbose", action="store_true", help="Print training progress")
    args = parser.parse_args()

    model = train(args)
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(model, indent=2, sort_keys=True), encoding="utf-8")
    print(json.dumps(model["metrics"], indent=2, sort_keys=True))
    print(f"Wrote {output}")


if __name__ == "__main__":
    main()
