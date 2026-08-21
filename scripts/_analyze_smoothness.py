import re
import sys

path = sys.argv[1] if len(sys.argv) > 1 else r"C:\Users\FREDRIK\Documents\enderslicercura\.build\brick-test\stack-arc.gcode"
lines = open(path, encoding="utf-8").read().splitlines()

bead_by_layer = {}
wall_by_layer = {}
layer = -1
current_type = None
for line in lines:
    if line.startswith(";LAYER:"):
        layer = int(line.split(":")[1])
        continue
    if line.startswith(";TYPE:"):
        current_type = line[6:]
        continue
    if line.startswith(("G0", "G1")):
        x = re.search(r"X([-0-9.]+)", line)
        y = re.search(r"Y([-0-9.]+)", line)
        if not (x and y):
            continue
        pt = (float(x.group(1)), float(y.group(1)))
        if current_type == "BEAD-ANGLE-OVERHANG":
            bead_by_layer.setdefault(layer, []).append(pt)
        elif current_type == "WALL-OUTER":
            wall_by_layer.setdefault(layer, []).append(pt)

print("layers with bead:", len(bead_by_layer), "with wall-outer:", len(wall_by_layer))


def bounds(pts):
    xs = [p[0] for p in pts]
    ys = [p[1] for p in pts]
    return min(xs), max(xs), min(ys), max(ys)


sample = 0
max_outside = 0.0
for layer in sorted(bead_by_layer):
    beads = bead_by_layer[layer]
    walls = wall_by_layer.get(layer, [])
    if not walls:
        continue
    bx = bounds(beads)
    wx = bounds(walls)
    outside = max(0.0, wx[0] - bx[0], bx[1] - wx[1])
    max_outside = max(max_outside, outside)
    if sample < 6:
        print(f"layer {layer}: bead pts={len(beads)} wall pts={len(walls)} bead exceeds wall outline by {outside:.3f} mm")
        sample += 1
print(f"max outside extent over sampled layers: {max_outside:.3f} mm")

# Sawtooth amplitude on the bead paths (inward press should be small).
all_beads = []
for pts in bead_by_layer.values():
    all_beads.extend(pts)
print("total bead points:", len(all_beads))
