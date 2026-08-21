import re
import sys

# Compare bead-section points against the TRUE per-layer outline derived from
# the STL cross-section (immune to wall-centerline insets).

def read_stl(path):
    text = open(path, encoding='utf-8', errors='replace').read()
    tris = re.findall(r'vertex\s+([-0-9.eE+]+)\s+([-0-9.eE+]+)\s+([-0-9.eE+]+)', text)
    pts = [(float(a), float(b), float(c)) for a, b, c in tris]
    return [tuple(pts[i:i+3]) for i in range(0, len(pts) - 2, 3)]

def cross_section(tris, z):
    segs = []
    for tri in tris:
        zs = [p[2] for p in tri]
        if min(zs) <= z <= max(zs) and max(zs) > min(zs):
            pts = []
            for a, b in ((tri[0], tri[1]), (tri[1], tri[2]), (tri[2], tri[0])):
                za, zb = a[2], b[2]
                if za == zb:
                    continue
                t = (z - za) / (zb - za)
                if 0.0 <= t <= 1.0:
                    pts.append((a[0] + t * (b[0] - a[0]), a[1] + t * (b[1] - a[1])))
            if len(pts) == 2:
                segs.append(tuple(pts))
    xs = [p[0] for s in segs for p in s]
    ys = [p[1] for s in segs for p in s]
    return (min(xs), max(xs), min(ys), max(ys)) if segs else None

def parse_bead(path):
    out = {}
    cur_z = None
    cur_type = None
    cur_bead = []
    def flush():
        if cur_z is not None and cur_bead:
            out.setdefault(cur_z, []).extend(cur_bead)
        cur_bead.clear()
    for line in open(path, encoding='utf-8').read().splitlines():
        if line.startswith(';TYPE:'):
            flush()
            cur_type = line[6:].strip()
            continue
        if line.startswith(';LAYER:'):
            flush()
            continue
        if not (line.startswith('G0') or line.startswith('G1')):
            continue
        z = re.search(r'Z([0-9.]+)', line)
        if z:
            cur_z = float(z.group(1))
        if cur_type != 'BEAD-ANGLE-OVERHANG':
            continue
        x = re.search(r'X(-?[0-9.]+)', line)
        y = re.search(r'Y(-?[0-9.]+)', line)
        if x and y and 'E' in line:
            cur_bead.append((float(x.group(1)), float(y.group(1))))
    flush()
    return out

gcode = sys.argv[1]
stl = sys.argv[2]
ox = float(sys.argv[3]) if len(sys.argv) > 3 else 117.5
oy = float(sys.argv[4]) if len(sys.argv) > 4 else 117.5
tol = float(sys.argv[5]) if len(sys.argv) > 5 else 0.02

tris = read_stl(stl)
bead = parse_bead(gcode)
print('bead layers:', len(bead))
bad = 0
worst = 0.0
for z in sorted(bead):
    if z is None:
        continue
    box = cross_section(tris, z)
    if box is None:
        continue
    minx, maxx, miny, maxy = box[0] + ox, box[1] + ox, box[2] + oy, box[3] + oy
    for x, y in bead[z]:
        d = max(minx - x, miny - y, x - maxx, y - maxy)
        if d > tol:
            bad += 1
            worst = max(worst, d)
            if bad <= 12:
                print(f'z={z:.2f} outside by {d:.4f} mm at ({x:.3f},{y:.3f}) outline=[{minx:.3f},{maxx:.3f}]x[{miny:.3f},{maxy:.3f}]')
print('layers with outside bead points:', bad, 'worst outside:', round(worst, 4), 'mm')
