#!/usr/bin/env python3
"""Package the three captured native RAW BW probes as portable test fixtures.

No output fitting or native execution. NumPy is used only to transpose the
captured V,H,S,4 Float32 grid to the core renderer's S,H,V,3 layout.
"""
import argparse
import hashlib
import json
import struct
from pathlib import Path
import numpy as np

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--native-dir', type=Path, default=Path('/Users/chenyixuan/ReverseWork/DxOFilmPack/evidence/raw-fidelity-20261103/native'))
args = parser.parse_args()
out = Path(__file__).resolve().parents[1] / 'core/local-data/raw-bw'
out.mkdir(exist_ok=True)
manifest = []
for name, label in (
    ('hiefiltre', 'providerbw-hiefiltre-p1040758-grid17-v1'),
    ('rolleiir400', 'providerbw-rolleiir400-p1176257-grid17-v1'),
    ('kodaktrix400', 'providerbw-kodaktrix400-p1176257-grid17-v1'),
):
    prefix = args.native_dir / ('small-grid-' + label)
    sources = {}
    def read(suffix):
        path = Path(str(prefix) + suffix)
        data = path.read_bytes()
        sources[path.name] = hashlib.sha256(data).hexdigest()
        return data
    interface = read('.prepared.actual-interface.bin')
    rendering = read('.prepared.rendering-data.bin')
    assert rendering[0] == 1 and interface[0x1659] == 1
    grid = np.frombuffer(read('.prepared.interface-storage-grid.bin'), '<f4').reshape(12, 91, 17, 4).transpose(2, 1, 0, 3)[..., :3].copy().astype('<f4').tobytes()
    post = read('.prepared.raw-one-or-three-6144-curve-0.f32le.bin')
    count = 4913
    data = (b'CXORAW01' + struct.pack('<4I', count, len(grid)//4, len(post)//4, 3)
            + interface[0x17b0:0x17b0+36] + interface[0x1714:0x1718] + interface[0x1710:0x1714] + grid + post)
    for suffix in ('.grid-input.q15.bin', '.grid-sensor-output.q15.bin', '.grid-full-output.q15.bin'):
        data += read(suffix)[:count*3*2]
    destination = out / (name + '.golden')
    destination.write_bytes(data)
    files = {destination.name: hashlib.sha256(data).hexdigest()}
    if name == 'kodaktrix400':
        # Test-only CXORBW01: ev, renderingEv, pre/post/extra matrices,
        # film and composed inverse Q15 curves, then native repaired grid.
        params = (b'CXORBW01' + interface[0x1714:0x1718] + rendering[0x28:0x2c]
                  + rendering[4:40] + rendering[0x80:0xa4] + interface[0x15c8:0x15ec]
                  + read('.prepared.rendering-curve-48.f32le.bin')
                  + read('.prepared.raw-one-or-three-6232-curve-0.f32le.bin') + grid)
        parameter_file = out / (name + '.parameters.bin')
        parameter_file.write_bytes(params)
        files[parameter_file.name] = hashlib.sha256(params).hexdigest()
    manifest.append({'files': files, 'nativeLabel': label, 'nativeTypeBw': 1, 'nativeRuntimeBw': 1, 'sources': sources})
(out/'manifest.json').write_text(json.dumps({'scope': 'Native RAW runtime BW probes and Tri-X production bake parameters; no fitted pixels or universal provider claims.', 'fixtures': manifest}, indent=2) + '\n')
