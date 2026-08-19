import re
import sys

path = r"C:\Users\FREDRIK\Documents\PrintShare\print_20260820_012154_621.gcode"
meta = {}
lines = []
with open(path, encoding="utf-8", errors="replace") as f:
    lines = f.readlines()

print("=== metadata ===")
for line in lines[:80]:
    if line.startswith(";") and ("ENDERSLICER" in line or "CURVI" in line or "LAYER_HEIGHT" in line):
        print(" ", line.strip())

print("=== structure ===")
layer = None
layers = {}
sentinel = None
for i, line in enumerate(lines):
    if line.strip() == ";ENDERSLICER_MACHINE_END_BEGIN":
        sentinel = i
    if line.startswith(";LAYER:"):
        layer = int(line.split(":")[1])
        layers.setdefault(layer, []),
    elif layer is not None and (line.startswith("G0") or line.startswith("G1")):
        m = re.search(r"X([-0-9.]+)", line)
        n = re.search(r"Y([-0-9.]+)", line)
        z = re.search(r"Z([-0-9.]+)", line)
        if m and n:
            layers.setdefault(layer, []).append(
                (float(m.group(1)), float(n.group(1)), float(z.group(1)) if z else None, line.strip()))

print("layers:", len(layers), "sentinel line:", sentinel)
print("=== per-layer Z range (top 12) ===")
ranges = []
for lyr, moves in layers.items():
    zs = [mv[2] for mv in moves if mv[2] is not None]
    if zs:
        ranges.append((lyr, max(zs) - min(zs), min(zs), max(zs)))
for lyr, rng, lo, hi in sorted(ranges, key=lambda t: -t[1])[:12]:
    print("  L%d: range=%.3f z=%.2f..%.2f" % (lyr, rng, lo, hi))

print("=== longest XY moves with their Z delta ===")
long_moves = []
for lyr, moves in layers.items():
    prev = None
    for mv in moves:
        if prev and mv[2] is not None and prev[2] is not None:
            d = ((mv[0] - prev[0]) ** 2 + (mv[1] - prev[1]) ** 2) ** 0.5
            if d > 3.0:
                long_moves.append((d, mv[2] - prev[2], lyr, prev, mv))
        prev = mv
for d, dz, lyr, a, b in sorted(long_moves, key=lambda t: -t[0])[:15]:
    print("  L%d: len=%.1f dz=%+.3f  (%s) -> (%s)" % (lyr, d, dz, a[3][:40], b[3][:40]))

print("=== total Z range of extrusion moves ===")
allz = [mv[2] for moves in layers.values() for mv in moves if mv[2] is not None]
print("  %.2f .. %.2f" % (min(allz), max(allz)))
