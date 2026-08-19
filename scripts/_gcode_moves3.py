import re

path = r"C:\Users\FREDRIK\Documents\PrintShare\print_20260820_012516_141.gcode"
prev = None
layer = None
lengths = []
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
                lengths.append((d, p[2] - prev[2], layer))
        prev = p

lengths.sort(key=lambda t: -t[0])
print("moves:", len(lengths))
for t in lengths[:14]:
    print("L%d len=%.2f dz=%+.3f" % (t[2] if t[2] is not None else -1, t[0], t[1]))
long = [t for t in lengths if t[0] > 1.0]
print("moves >1mm:", len(long), "max |dz|:", max((abs(t[1]) for t in long), default=0.0))
