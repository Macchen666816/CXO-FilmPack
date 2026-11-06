#!/usr/bin/env python3
"""Rebuild RAW BW flags and fixed-film pre transforms from verified resources.

This updates routing semantics and pre transforms, never RAW curves/post matrices. Run after rebuilding
raw-films/catalog.json so a catalog regeneration cannot silently drop BW flags.
"""
import argparse
import hashlib
import json
import numpy as np
import struct
from pathlib import Path

root = Path(__file__).resolve().parents[1] / 'core/local-data'
path = root / 'raw-films/catalog.json'
catalog = json.loads(path.read_text())
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--source', type=Path, default=Path('/Users/chenyixuan/ReverseWork/DxOFilmPack/data/v2/films.json'))
args = parser.parse_args()
source_films = {film['id']: film for film in json.loads(args.source.read_text())['films']}
elite_id = 'ColorModeArgentiqueKodakEliteColor400'
inverse_elite = np.linalg.inv(np.asarray(source_films[elite_id]['matrixA'], dtype=np.float64).reshape(3, 3))
dynamic_ids = {'ColorModeArgentique' + token for token in ('Astia', 'Provia', 'Velvia', 'Ekta100VS', 'Kod64')}
counts = {}
for film in catalog['films']:
    matches = list(root.glob('*/' + film['id'] + '.cxohsv'))
    assert len(matches) == 1, (film['id'], matches)
    source = matches[0]
    data = source.read_bytes()
    assert data[:8] in (b'CXOHSV01', b'CXOHSV02', b'CXOHSV03')
    flags = struct.unpack_from('<3I', data, 28) if data[:8] != b'CXOHSV01' else (0, 0, 0)
    assert all(flag in (0, 1) for flag in flags)
    additions = source_films[film['id']].get('initializer_state', {}).get('relative_float_additions', [])
    initializer_ev_delta = sum(float(item['delta']) for item in additions if item['offset'] == '0x78')
    film['initializerEvDelta'] = initializer_ev_delta
    # Negative-EV providers change target matrices/rendering EV. Keep the old
    # sRGB RAW bridge until those additional native rules are reconstructed.
    film['controlledRaw'] = initializer_ev_delta == 0.0
    film['typeBw'] = bool(flags[1])
    film['runtimeBw'] = bool(flags[2])
    # Five dynamic providers already use their own captured camera/film pre.
    # Fixed providers use targetM1 * inverse(EliteM1) * capturedElitePre.
    transform = np.eye(3) if not film['controlledRaw'] or film['id'] in dynamic_ids or film['id'] == elite_id else np.asarray(source_films[film['id']]['matrixA'], dtype=np.float64).reshape(3, 3) @ inverse_elite
    film['preTransform'] = transform.reshape(-1).tolist()
    counts[flags[1:]] = counts.get(flags[1:], 0) + 1
catalog['bwFlagSource'] = 'Verified CXOHSV02/03 header type-BW and runtime-BW; tools/sync_raw_bw_flags.py. RAW Tri-X native RenderingData[0]/interface+0x1659 corroborates both flags.'
catalog['preTransformSource'] = {'formula': 'controlled fixed EV0: film.matrixA * inverse(Elite.matrixA); dynamic5, Elite and disabled negative-EV: identity', 'source': str(args.source), 'sha256': hashlib.sha256(args.source.read_bytes()).hexdigest(), 'nativeEvidence': 'Engine 0x4b399c; native Tri-X provider targetM1 exactly equals extracted matrixA'}
path.write_text(json.dumps(catalog, ensure_ascii=False, indent=2) + '\n')
print('RAW BW flag counts:', counts)
