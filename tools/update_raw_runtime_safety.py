#!/usr/bin/env python3
"""Apply bounded Kotlin runtime repair safety scans to the RAW catalog.

Run :core:verifyRawRuntimeBake and :core:verifyRawThreeCurveBake first.
Safety means finite, nonnegative repaired S/V at the captured camera anchors;
it does not establish native parity or enable a film/camera route.
"""
import json
from pathlib import Path

root = Path(__file__).resolve().parents[1]
scan_dir = root / 'core/build/verification'
scans = {}
for name in ('verifyRawRuntimeBake', 'verifyRawThreeCurveBake'):
    scans.update(json.loads((scan_dir / (name + '-safety.json')).read_text()))
path = root / 'core/local-data/raw-films/catalog.json'
catalog = json.loads(path.read_text())
updated = 0
for film in catalog['films']:
    film['runtimeSafe'] = bool(film.get('controlledRaw') and scans.get(film['id'], False))
    if film['id'] in scans:
        updated += 1
assert updated == 75, updated
catalog['runtimeSafeScope'] = 'Kotlin repaired S/V nonnegative scan over 3 captured Elite S1RII WB anchors, using preTransform and explicit typeBw. The five dynamic-direct contexts retain explicit experimental signed mode. Safety does not imply native parameter/render parity.'
path.write_text(json.dumps(catalog, ensure_ascii=False, indent=2) + '\n')
print('Updated bounded runtimeSafe:', updated, 'safe:', sum(scans.values()), 'signed:', sum(not x for x in scans.values()))
