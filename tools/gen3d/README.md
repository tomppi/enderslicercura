# gen3d - local image-to-3D service

The PC-side half of "generate a printable model from a photo". The app sends an
image, this returns an STL it can slice. Everything runs on your own machine;
nothing leaves it.

## Why a service and not a direct integration

The app speaks one small, stable protocol; the generator behind it is
replaceable. InstantMesh, TripoSR or Hunyuan3D-2.1 can each be swapped in by
changing `--generator`, and the app never learns the difference.

    POST /generate            raw image bytes            -> {"job": "<id>"}
    GET  /jobs/<id>           -> {"state": "queued|running|done|failed", "detail": ...}
    GET  /jobs/<id>/model.stl -> the mesh (only when state is done)

One job at a time: a single GPU is the resource being shared.

## What the cleanup step does

Generators emit OBJ/GLB with vertex colours, arbitrary units, and usually a
non-watertight, over-dense surface. The app imports STL only, so
`mesh_cleanup.py` merges vertices, drops degenerate and duplicate faces, fixes
normals, fills holes, decimates to a face budget, scales to a real height in
millimetres and sits the result on the bed. Colours are dropped - STL has none,
and the slicer only needs geometry.

## Running it

Both the generator and this service want the same Python:

    pip install trimesh scipy fast-simplification

    python server.py --generator instantmesh \
        --generator-dir ~/InstantMesh \
        --config configs/instant-mesh-base.yaml \
        --host 0.0.0.0 --port 8765 \
        --faces 250000 --height-mm 60

Bind it to your LAN or tailnet only - there is no authentication, by design:
it is a single-user tool on your own network.

### Backends

| `--generator` | command it runs | notes |
|---|---|---|
| `instantmesh` | `python run.py <config> <image> --output_path <dir>` | needs CUDA >= 12.1 and its pinned xformers; on 8 GB use `instant-mesh-base.yaml` |
| `triposr` | `python run.py <image> --output-dir <dir>` | ~6 GB VRAM, fast, MIT |

### Windows note

InstantMesh's README targets Linux and pins `xformers==0.0.22.post7`, which has
no Windows wheel, so on a Windows box run it under WSL2 with CUDA passthrough.
This service itself is plain Python and runs anywhere the generator runs.
