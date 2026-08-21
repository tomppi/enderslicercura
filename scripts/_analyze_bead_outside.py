import re
import sys

path = sys.argv[1]
tol = float(sys.argv[2]) if len(sys.argv) > 2 else 0.03

lines = open(path, encoding="utf-8").read().splitlines()

layers = {}
cur_z = None
cur_type = None
cur_pts = None
layer_order = []

def flush():
    global cur_pts, cur_type
    if cur_z is None or cur_pts is None:
        cur_pts = None
        return
    data = layers.setdefault(cur_z, {"bead": [], "other": []})
    key = "bead" if cur_type == "BEAD-ANGLE-OVERHANG" else "other"
    data[key].extend(cur_pts)
    cur_pts = None

for line in lines:
    if line.startswith(";TYPE:"):
        flush()
        cur_type = line[6:].strip()
        cur_pts = []
        continue
    if line.startswith(";LAYER:"):
        flush()
        cur_z = float(line.split(":")[1])
        if cur_z not in layer_order:
            layer_order.append(cur_z)
        continue
    if not (line.startswith("G0") or line.startswith("G1")):
        continue
    x = re.search(r"X(-?[0-9.]+)", line)
    y = re.search(r"Y(-?[0-9.]+)", line)
    if not (x and y):
        continue
    if "E" not in line:
        continue  # travel
    if cur_pts is None:
        cur_pts = []
    cur_pts.append((float(x.group(1)), float(y.group(1))))
flush()

def box(pts):
    xs = [p[0] for p in pts]
    ys = [p[1] for p in pts]
    return min(xs), min(ys), max(xs), max(ys)

print("layers:", len(layer_order))
total_out = 0
worst = []
for z in layer_order:
    data = layers.get(z)
    if data is None:
        continue
    bead = data["bead"]
    other = data["other"]
    if not bead or not other:
        continue
    minx, miny, maxx, maxy = box(other)
    out = 0
    max_d = 0.0
    for x, y in bead:
        d = max(minx - x, miny - y, x - maxx, y - maxy)
        if d > tol:
            out += 1
            max_d = max(max_d, d)
    if out:
        total_out += out
        worst.append((z, out, max_d, len(bead)))
        print(f"z={z:.2f} bead_pts={len(bead)} outside={out} max_outside={max_d:.4f} mm")
print(f"TOTAL layers with outside bead points: {len(worst)}")
