#!/usr/bin/env python3
"""End-to-end Real-Time Dictation latency: spoken word -> visible on the host.

Inputs:
  report.json   dictation session report pulled from the phone (has streamStartWallMs and
                per-commit word end times in audio seconds)
  capture.json  tools/host_capture.py output (host wall clock for every received character)
  offset_ms     phone_clock - host_clock in ms (scripts/clock_offset.sh)

For each word that appears both in the phone's commit records and in the host text (aligned in
order), it reports:
  spoken->commit  phone commit time - word end (audio time mapped to phone wall clock)
  commit->host    host arrival of the word's last character - phone commit time
  spoken->host    total
Word end = audio time of the word's last recognizer token (approximate; transducer token times
mark the frame where the token was emitted).
"""
import difflib
import json
import re
import statistics
import sys


def norm(w):
    return re.sub(r"[^\w']", "", w.lower())


def stats(xs):
    if not xs:
        return "n/a"
    xs = sorted(xs)
    pick = lambda p: xs[min(len(xs) - 1, int(round(p / 100 * (len(xs) - 1))))]
    return f"n={len(xs)} min={xs[0]:.0f} p50={pick(50):.0f} p90={pick(90):.0f} max={xs[-1]:.0f} mean={statistics.mean(xs):.0f}"


report = json.load(open(sys.argv[1]))
cap = json.load(open(sys.argv[2]))
offset = float(sys.argv[3])

start = report["streamStartWallMs"]
phone_words = []  # (word, commitWallMs, spokenEndWallMs or None)
for c in report["commits"]:
    for w, end in zip(c["words"], c["wordEndSec"]):
        phone_words.append((w, c["wallMs"], None if end is None else start + end * 1000))

# Host words with the (phone-clock) time their last character arrived.
host_words = []
cur, last_t = "", None
for t, _, ch in cap["events"]:
    if not ch:
        continue
    t_phone = (cap["start_wall"] + t) * 1000 + offset
    if ch.isspace():
        if cur:
            host_words.append((cur, last_t))
        cur = ""
    else:
        cur += ch
        last_t = t_phone
if cur:
    host_words.append((cur, last_t))

a = [norm(w) for w, *_ in phone_words]
b = [norm(w) for w, _ in host_words]
sm = difflib.SequenceMatcher(a=a, b=b, autojunk=False)
s2c, c2h, s2h = [], [], []
matched = 0
for blk in sm.get_matching_blocks():
    for k in range(blk.size):
        w, commit, spoken = phone_words[blk.a + k]
        _, host_t = host_words[blk.b + k]
        matched += 1
        c2h.append(host_t - commit)
        if spoken is not None:
            s2c.append(commit - spoken)
            s2h.append(host_t - spoken)

print(f"phone words={len(phone_words)} host words={len(host_words)} aligned={matched}")
print(f"host text matches committed text exactly: {cap['received_text'] == report.get('committedText')}")
print(f"spoken->commit ms: {stats(s2c)}")
print(f"commit->host   ms: {stats(c2h)}")
print(f"spoken->host   ms: {stats(s2h)}")
