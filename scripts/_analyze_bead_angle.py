import math
import re

path = r"C:\Users\FREDRIK\Documents\enderslicercura\.build\brick-test\bead-cliff70.gcode"
lines = open(path, encoding="utf-8").read().splitlines()
sections = []
cur = None
for line in lines:
    if line.startswith(";TYPE:"):
        if line.startswith(";TYPE:BEAD-ANGLE-OVERHANG"):
            cur = []
            sections.append(cur)
        else:
            cur = None
    elif cur is not None and (line.startswith("G0") or line.startswith("G1")):
        cur.append(line)
print("bead sections:", len(sections), "moves in first:", len(sections[0]) if sections else 0)
for idx in (0, 1, 2):
    if idx >= len(sections):
        break
    s = sections[idx]
    pts = []
    for m in s[:300]:
        x = re.search(r"X([-0-9.]+)", m)
        y = re.search(r"Y([-0-9.]+)", m)
        if x and y:
            pts.append((float(x.group(1)), float(y.group(1))))
    if len(pts) > 2:
        x0, y0 = pts[0]
        x1, y1 = pts[-1]
        dx, dy = x1 - x0, y1 - y0
        length = math.hypot(dx, dy) or 1.0
        devs = []
        for x, y in pts:
            t = ((x - x0) * dx + (y - y0) * dy) / (length * length)
            px, py = x0 + dx * t, y0 + dy * t
            devs.append(math.hypot(x - px, y - py))
        devs.sort()
        print(f"section {idx}: points={len(pts)} max perpendicular dev={devs[-1]:.4f} mm p90={devs[int(len(devs) * 0.9)]:.4f} mm")
        print("  first moves:", pts[:8])
