import re

path = r"C:\Users\FREDRIK\Documents\PrintShare\print_20260820_012516_141.gcode"
print("=== metadata ===")
with open(path, encoding="utf-8", errors="replace") as f:
    for line in f:
        if line.startswith(";") and ("ENDERSLICER" in line or "CURVI" in line or "LAYER_HEIGHT" in line):
            print(" ", line.strip())
        if line.startswith(";LAYER:") and int(line.split(":")[1]) > 30:
            break

prev = None
lengths = []
layer = None
layers = {}
with open(path, encoding="utf-8", errors="replace") as f:
    for line in f:
        if line.startswith(";LAYER:"):
            layer = int(line.split(":")[1])
            continue
        if not (line.startswith("G0") or line.startswith("G1")):
            continue
        m = re.search(r"X([-0-9.]+)", line)
        n = re.search(r"Y([-0-9.]+)", line)
        z = re.search(r"Z([-0-9.]+)", line)
        if not (m and n):
            continue
        p = (float(m.group(1)), float(n.group(1)), float(z.group(1)) if z else None)
        if prev and p[2] is not None and prev[2] is not None:
            d = ((p[0] - prev[0]) ** 2 + (p[1] - prev[1]) ** 2) ** 0.5
            if d > 0.01:
                lengths.append((d, p[2] - prev[2], layer, prev, p))
        prev = p

lengths.sort(key=lambda t: -t[0])
print("moves:", len(lengths), " layers:", max((t[2] for t in lengths if t[2] is not None), default=0))
print("=== top 14 moves by length (mm) with Z delta ===")
for d, dz, lyr, a, b in lengths[:14]:
    print("  L%d: len=%.2f dz=%+.3f" % (lyr, d, dz))
long = [t for t in lengths if t[0] > 1.0]
print("moves longer than 1mm:", len(long))
print("max |dz| among moves >1mm:", max((abs(t[1]) for t in long), default=0))
print("=== per-layer Z range (top 8) ===")
ranges = []
seen = {}
for t in lengths:
    lyr = t[2]
    if lyr is None:
        continue
    lo, hi = seen.get(lyr, (9e9, -9e9))
    seen[lyr] = (min(lo, t[3][2], t[4][2]), max(hi, t[3][2], t[4][2]))
for lyr, (lo, hi) in sorted(seen.items(), key=lambda kv: -(kv[1][1] - kv[1][0]))[:8]:
    print("  L%d: range=%.3f z=%.2f..%.2f" % (lyr, hi - lo, lo, hi))
