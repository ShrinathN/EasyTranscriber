#!/usr/bin/env python3
"""Host-side capture window for HID typing tests (Linux/X11, Tk).

Opens a text window, takes a global X keyboard grab so keystrokes cannot reach any other window,
records every received character with a host timestamp, and exits after --idle seconds without
input (or --timeout overall). Writes a JSON report with the received text and timing.

NOTE: while the grab is active, the physical keyboard is captured too. Do not type during a test.

Usage: host_capture.py OUTPUT.json [--idle 4] [--timeout 90] [--stop-file PATH]

With --idle 0 the window never closes on its own for inactivity; it closes when --stop-file
appears (or at --timeout). Use that for tests with long pauses (e.g. disconnect/reconnect), so the
window can't close while the phone may still type.
"""
import argparse
import json
import time
import tkinter as tk

parser = argparse.ArgumentParser()
parser.add_argument("output")
parser.add_argument("--idle", type=float, default=4.0)
parser.add_argument("--timeout", type=float, default=90.0)
parser.add_argument("--stop-file", default=None)
args = parser.parse_args()

KEYSYM_TEXT = {"Return": "\n", "KP_Enter": "\n", "Tab": "\t"}
MODIFIERS = {"Shift_L", "Shift_R", "Control_L", "Control_R", "Alt_L", "Alt_R", "Super_L", "Super_R"}

start = time.monotonic()
start_wall = time.time()  # wall clock at monotonic `start`, for cross-device latency analysis
events = []  # (seconds since start, keysym, char)
received = []

root = tk.Tk()
root.title("HID capture - do not type")
text = tk.Text(root, width=100, height=12)
text.pack()
status = tk.Label(root, text="Waiting for keystrokes...")
status.pack()


def on_key(event):
    now = time.monotonic() - start
    if event.keysym in MODIFIERS:
        return "break"
    if event.keysym == "BackSpace":
        ch = "\b"
        if received:
            received.pop()
    else:
        ch = KEYSYM_TEXT.get(event.keysym, event.char)
        if ch:
            received.append(ch)
    events.append((round(now, 4), event.keysym, ch))
    text.delete("1.0", tk.END)
    text.insert(tk.END, "".join(received))
    status.config(text=f"{len(received)} chars")
    return "break"


def finish():
    try:
        root.grab_release()
    except tk.TclError:
        pass
    char_times = [t for (t, _, c) in events if c]
    report = {
        "start_wall": start_wall,
        "received_text": "".join(received),
        "received_chars": len(received),
        "event_count": len(events),
        "first_key_s": char_times[0] if char_times else None,
        "last_key_s": char_times[-1] if char_times else None,
        "mean_interval_ms": (
            round(1000 * (char_times[-1] - char_times[0]) / (len(char_times) - 1), 2)
            if len(char_times) > 1 else None
        ),
        "events": events,
    }
    with open(args.output, "w") as f:
        json.dump(report, f, indent=1)
    root.destroy()


def tick():
    now = time.monotonic() - start
    last = events[-1][0] if events else None
    import os
    stop = args.stop_file is not None and os.path.exists(args.stop_file)
    idle = args.idle > 0 and last is not None and now - last > args.idle
    if now > args.timeout or stop or idle:
        finish()
        return
    root.after(200, tick)


def take_grab():
    root.lift()
    root.focus_force()
    text.focus_set()
    try:
        root.grab_set_global()
    except tk.TclError:
        root.after(100, take_grab)  # window not yet viewable


text.bind("<Key>", on_key)
root.after(300, take_grab)
root.after(200, tick)
root.mainloop()
