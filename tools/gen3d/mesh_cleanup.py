#!/usr/bin/env python3
"""Turn a generator's mesh into something the slicer can actually use.

Image-to-3D models emit OBJ/GLB with vertex colours in arbitrary units, often
non-watertight and far too dense to preview. The app imports STL only (a picked
model is staged as .stl and handed to the engines that way), so this step is
where the conversion, the repair, the decimation and the millimetre scaling
happen - before the mesh ever reaches the phone.

    python mesh_cleanup.py in.obj out.stl --target-height-mm 60 --faces 250000
"""
from __future__ import annotations

import argparse
import sys

import trimesh


def load_any(path: str) -> trimesh.Trimesh:
    loaded = trimesh.load(path, force="scene")
    if isinstance(loaded, trimesh.Scene):
        meshes = [g for g in loaded.geometry.values() if isinstance(g, trimesh.Trimesh)]
        if not meshes:
            raise SystemExit(f"no mesh geometry in {path}")
        mesh = trimesh.util.concatenate(meshes)
    else:
        mesh = loaded
    if mesh.faces.shape[0] == 0:
        raise SystemExit(f"mesh in {path} has no faces")
    return mesh


def clean(mesh: trimesh.Trimesh, faces: int | None, target_height_mm: float | None) -> trimesh.Trimesh:
    # Generated meshes routinely carry duplicate/degenerate faces and flipped
    # normals; the slicer copes badly with both.
    mesh.merge_vertices()
    mesh.update_faces(mesh.nondegenerate_faces())
    mesh.update_faces(mesh.unique_faces())
    mesh.remove_unreferenced_vertices()
    mesh.fix_normals()
    trimesh.repair.fill_holes(mesh)
    trimesh.repair.fix_inversion(mesh)

    if faces and mesh.faces.shape[0] > faces:
        try:
            mesh = mesh.simplify_quadric_decimation(face_count=faces)
        except BaseException as error:  # fast-simplification is optional
            print(f"warning: decimation unavailable ({error}); keeping {mesh.faces.shape[0]} faces", file=sys.stderr)

    if target_height_mm:
        height = float(mesh.extents[2])
        if height > 0:
            mesh.apply_scale(target_height_mm / height)

    # Sit it on the bed, centred, so the app's placement starts from a sane spot.
    mesh.apply_translation(-mesh.bounds[0])
    centre = mesh.bounds.mean(axis=0)
    mesh.apply_translation([-centre[0], -centre[1], 0.0])
    return mesh


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source")
    parser.add_argument("target")
    parser.add_argument("--faces", type=int, default=250_000, help="decimate above this face count (0 disables)")
    parser.add_argument("--target-height-mm", type=float, default=None, help="scale so the print is this tall")
    args = parser.parse_args()

    mesh = clean(load_any(args.source), args.faces or None, args.target_height_mm)
    mesh.export(args.target, file_type="stl")
    print(
        f"{args.target}: {mesh.faces.shape[0]} faces, "
        f"{mesh.extents[0]:.1f} x {mesh.extents[1]:.1f} x {mesh.extents[2]:.1f} mm, "
        f"watertight={mesh.is_watertight}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
