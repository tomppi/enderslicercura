import re
import sys

# Compare bead-slice extrusion points against a reference (feature OFF) slice
# of the same model: the reference walls trace the true outline per layer.

def parse(path):
    layers = []
    cur_type = None
    cur_pts = []
    bead = []
    other = []
    for line in open(path, encoding="utf-8").read().splitlines():
        if line.startswith(";TYPE:"):
            cur_type = line[6:].strip()
            continue
        if line.startswith(";LAYER:"):
            if cur_pts:
                (bead if cur_type == "BEAD-ANGLE-OVERHANG" else other).extend(cur_pts)
            layers.append(len(bead) + len(other))
            cur_pts = []
            continue
        if not (line.startswith("G0") or line.startswith("G1")):
            continue
        x = re.search(r"X(-?[0-9.]+)", line)
        y = re.search(r"Y(-?[0-9.]+)", line)
        if not (x and y) or "E" not in line:
            continue
        cur_pts.append((float(x.group(1)), float(y.group(1))))
    if cur_pts:
        (bead if cur_type == "BEAD-ANGLE-OVERHANG" else other).extend(cur_pts)
    return layers, bead, other

def split(path, bead_pts, other_pts):
    layers, bead, other = parse(path)
    # first layer header comes before any points; align using cumulative counts
    bead_layers = []
    other_layers = []
    b = o = 0
    last = 0
    for total in layers[1:] + [len(bead) + len(other)]:
        bead_layers.append([])
        other_layers.append([])
        # all points appended during this layer: they arrive interleaved, so use
        # order info is lost; rebuild by count per layer instead
    return None

# simpler: parse per-layer lists directly
def parse_layers(path):
    out = {}
    cur_z = None
    cur_type = None
    cur_bead = []
    cur_other = []
    def flush():
        if cur_z is not None and (cur_bead or cur_other):
            out.setdefault(cur_z, {"bead": [], "other": []})
            out[cur_z]["bead"].extend(cur_bead)
            out[cur_z]["other"].extend(cur_other)
        cur_bead.clear()
        cur_other.clear()
    for line in open(path, encoding="utf-8").read().splitlines():
        if line.startswith(";TYPE:"):
            flush()
            cur_type = line[6:].strip()
            continue
        if line.startswith(";LAYER:"):
            flush()
            cur_z = int(line.split(":")[1])
            continue
        if not (line.startswith("G0") or line.startswith("G1")):
            continue
        x = re.search(r"X(-?[0-9.]+)", line)
        y = re.search(r"Y(-?[0-9.]+)", line)
        if not (x and y) or "E" not in line:
            continue
        (cur_bead if cur_type == "BEAD-ANGLE-OVERHANG" else cur_other).append((float(x.group(1)), float(y.group(1))))
    flush()
    return out

def box(pts):
    xs = [p[0] for p in pts]
    ys = [p[1] for p in pts]
    return min(xs), min(ys), max(xs), max(ys)

bead_path = sys.argv[1]
ref_path = sys.argv[2]
tol = float(sys.argv[3]) if len(sys.argv) > 3 else 0.02

bead_layers = parse_layers(bead_path)
ref_layers = parse_layers(ref_path)
print("bead layers:", len(bead_layers), "ref layers:", len(ref_layers))
bad = 0
for z in sorted(bead_layers):
    if z not in ref_layers:
        continue
    ref_pts = ref_layers[z]["bead"] + ref_layers[z]["other"]
    if len(ref_pts) < 4:
        continue
    minx, miny, maxx, maxy = box(ref_pts)
    for kind in ("bead", "other"):
        for x, y in bead_layers[z][kind]:
            d = max(minx - x, miny - y, x - maxx, y - maxy)
            if d > tol:
                bad += 1
                print(f"layer {z} {kind} outside by {d:.4f} mm at ({x:.3f},{y:.3f}) box=[{minx:.3f},{maxx:.3f}]x[{miny:.3f},{maxy:.3f}]")
                if bad > 40:
                    print("... more")
                    sys.exit(1)
                break
print("total outside points (layers):", bad)
