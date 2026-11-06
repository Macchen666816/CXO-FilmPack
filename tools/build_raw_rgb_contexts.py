#!/usr/bin/env python3
"""Package six native RAW RGB contexts without sampled output grids or fitting.

Every stored Float parameter is copied byte-for-byte from an engaged native
RenderingData/interface field. Native Float Q15 curve samples are checked to
be integral, then losslessly stored as signed int16 with their native guard.
"""
import argparse
import hashlib
import json
from pathlib import Path
import struct
import numpy as np

MAGIC = b'CXORRGB1'
VERSION = 1
Q15_I16_ENCODING = 1
SAMPLES = 32769
HEADER_BYTES = 144
ROOT = Path(__file__).resolve().parents[1]

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--native-dir', type=Path, default=Path('/Users/chenyixuan/ReverseWork/DxOFilmPack/evidence/raw-fidelity-20261103/native'))
parser.add_argument('--output-dir', type=Path, default=ROOT / 'core/local-data/raw-rgb-contexts')
parser.add_argument('--check', action='store_true', help='Verify committed assets instead of writing them')
args = parser.parse_args()
records = []

def emit(path, data):
    if args.check:
        assert path.read_bytes() == data, f'Generated resource differs: {path}'
    else:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)

for slug, token in (('eir', 'KodakEIR'), ('hie', 'KodakHIE'), ('tealandpink', 'CineTealAndPink')):
    for photo, temperature in (('p1040758', 4199), ('p1176257', 5485)):
        label = f'small-grid-s1rii-{photo}-{slug}-raw-grid17-v1'
        prefix = args.native_dir / label
        sources = {}
        def read(suffix):
            path = Path(str(prefix) + suffix)
            data = path.read_bytes()
            sources[path.name] = hashlib.sha256(data).hexdigest()
            return data
        interface = read('.prepared.actual-interface.bin')
        rendering = read('.prepared.rendering-data.bin')
        assert len(interface) >= 0x1718 and len(rendering) >= 0xa4
        kind = struct.unpack_from('<I', rendering, 0x78)[0]
        assert kind in (0, 1)
        count = 1 if kind == 0 else 3
        assert count == (3 if slug == 'tealandpink' else 1)
        runtime_bw = interface[0x1659]
        assert runtime_bw == (1 if slug == 'hie' else 0)
        ev = interface[0x1714:0x1718]
        rendering_ev = rendering[0x28:0x2c]
        matrices = rendering[4:40] + rendering[0x80:0xa4] + interface[0x15c8:0x15ec]
        scalar_parameters = np.frombuffer(ev + rendering_ev + matrices, '<f4')
        assert scalar_parameters.size == 29 and np.isfinite(scalar_parameters).all()
        payload = bytearray()
        curve_hashes = []
        for channel in range(count):
            raw = read(f'.prepared.rendering-curve-{48 + 24 * channel}.f32le.bin')
            curve = np.frombuffer(raw, '<f4')
            assert curve.shape == (SAMPLES,) and np.isfinite(curve).all()
            assert (curve >= 0).all() and (curve <= 32767).all()
            assert np.equal(curve, np.rint(curve)).all()
            assert curve[-1].view('<u4') == curve[-2].view('<u4')
            encoded = curve.astype('<i2').tobytes()
            assert np.array_equal(np.frombuffer(encoded, '<i2').astype('<f4').view('<u4'), curve.view('<u4'))
            payload += encoded
            curve_hashes.append(hashlib.sha256(raw).hexdigest())
        header = MAGIC + struct.pack('<5I', VERSION, runtime_bw, count, SAMPLES, Q15_I16_ENCODING) + ev + rendering_ev + matrices
        assert len(header) == HEADER_BYTES
        asset = header + payload
        suffix = '-landscape' if photo == 'p1176257' else ''
        destination = args.output_dir / f's1rii-{slug}{suffix}.cxorrgb'
        emit(destination, asset)
        records.append({
            'filmId': 'ColorMode' + ('CineTealAndPink' if slug == 'tealandpink' else 'Argentique' + token),
            'token': token, 'file': destination.name, 'camera': 'DC-S1RM2', 'sourcePhoto': photo.upper(),
            'wbAnchorKelvin': temperature, 'nativeLabel': label, 'curveCount': count,
            'runtimeBw': bool(runtime_bw), 'ev': struct.unpack('<f', ev)[0],
            'renderingEv': struct.unpack('<f', rendering_ev)[0], 'byteCount': len(asset),
            'sha256': hashlib.sha256(asset).hexdigest(), 'nativeCurveSha256': curve_hashes,
            'sourceSha256': sources,
        })
manifest = {
    'schema': 'CXORRGB1_MANIFEST', 'version': VERSION, 'count': len(records),
    'assetMagic': MAGIC.decode(), 'headerBytes': HEADER_BYTES, 'curveEncoding': 'signed-int16 Q15, 32769 samples including native guard; exact Float32 restoration',
    'parameters': {'ev': 'interface +0x1714', 'renderingEv': 'RenderingData +0x28', 'preMatrix': 'RenderingData +0x04',
                   'postMatrix': 'RenderingData +0x80', 'extraMatrix': 'interface +0x15c8', 'runtimeBw': 'interface +0x1659',
                   'curveCount': 'RenderingData +0x78 kind 0=>1, kind 1=>3', 'filmCurves': 'RenderingData curve carriers +0x30/+0x48/+0x60'},
    'fittedParameters': 0, 'containsSampledOutputGrid': False,
    'scope': 'Six captured S1RII strength100 RAW RGB contexts at two WB anchors. Parameters are film-specific; arbitrary WB/EV/profile and S9 are not inferred. TealAndPink rendering EV is 0.7 and has three curves.',
    'contexts': records,
}
emit(args.output_dir / 'manifest.json', (json.dumps(manifest, ensure_ascii=False, indent=2) + '\n').encode())
print(json.dumps({'contexts': len(records), 'assetBytes': sum(record['byteCount'] for record in records), 'checked': args.check}))
