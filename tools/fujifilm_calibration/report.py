"""Serialize calibration measurements without hiding disagreement in one score."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any, Mapping


def write_json(result: Mapping[str, Any], path: str | Path) -> None:
    Path(path).write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def markdown_report(result: Mapping[str, Any], title: str = "Calibration report") -> str:
    lines = [f"# {title}", "", "Metrics are intentionally reported separately; no composite score is calculated.", ""]
    for name in ("tone", "grain", "colour"):
        metric = result.get(name)
        if not metric:
            continue
        lines += [f"## {name.title()}", ""]
        rows = metric.get("deltas", metric.get("patches", []))
        if name == "colour":
            lines.append("| Patch | MAE | ΔR | ΔG | ΔB |\n|---|---:|---:|---:|---:|")
            lines += [f"| {i + 1} | {r['mae']:.6f} | " + " | ".join(f"{v:.6f}" for v in r["delta_rgb"]) + " |" for i, r in enumerate(rows)]
        else:
            key = "mean_delta" if name == "tone" else "rms_delta"
            lines.append(f"| Region | {key} |\n|---|---:|")
            lines += [f"| {i + 1} | {r[key]:.6f} |" for i, r in enumerate(rows)]
        lines.append("")
    return "\n".join(lines)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("result_json")
    parser.add_argument("--output", "-o")
    args = parser.parse_args(argv)
    result = json.loads(Path(args.result_json).read_text(encoding="utf-8"))
    text = markdown_report(result)
    if args.output:
        Path(args.output).write_text(text + "\n", encoding="utf-8")
    else:
        print(text)

if __name__ == "__main__":
    main()
