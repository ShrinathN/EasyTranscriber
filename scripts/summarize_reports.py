#!/usr/bin/env python3
"""Prints a Markdown table summarizing streaming session reports (JSON files pulled from the device)."""
import glob
import json
import sys


def f(v, nd=0):
    return "-" if v is None else f"{v:.{nd}f}"


rows = []
for path in sorted(glob.glob(sys.argv[1] + "/*.json")):
    r = json.load(open(path))
    t = r["tokenLatencyMs"]
    lag = r["frameLagMs"]
    model = r["model"].replace("sherpa-onnx-", "").replace("streaming-", "").replace("fast-conformer-transducer-", "fc-")
    rows.append("| {} | {} | {} | {} | {} | {} | {} | {} | {} | {} | {} | {} | {} | {} | {} |".format(
        path.split("/")[-1].replace("stream-", "").replace(".json", ""), model, r["threads"], r["modelLoadMs"],
        "test" if r["testInput"] else "mic", f(r["audioSec"], 1), f(r["rtf"], 3),
        f(t.get("min")), f(t.get("p50")), f(t.get("p90")), f(t.get("max")),
        f(lag.get("p90")), r["framesDropped"],
        f(r.get("wer", None) * 100 if r.get("wer") is not None else None, 1),
        "{} MB / {}%".format(round(max(r["pssKbSamples"]) / 1024), f(r["cpuPercentOfOneCore"])),
    ))
print("| Report | Model | Thr | Load ms | Input | Audio s | RTF | Tok min | Tok p50 | Tok p90 | Tok max | Lag p90 | Drop | WER % | Peak PSS / CPU (1 core=100%) |")
print("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|")
print("\n".join(rows))
