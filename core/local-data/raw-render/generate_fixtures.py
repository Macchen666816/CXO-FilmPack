#!/usr/bin/env python3
"""Copy explicitly captured native parameters/oracles into verification-only fixtures."""
import argparse
from pathlib import Path
import struct
import numpy as np

p=argparse.ArgumentParser(description=__doc__)
p.add_argument('research_root',type=Path)
args=p.parse_args()
native=args.research_root/'evidence/raw-fidelity-20261103/native'
out=Path(__file__).parent
for name,prefix,tag,kind,count in (
    ('elite-photo512',native/'dynamic-compute-s1rii-p1040758-elite-sensor512.tif','after','photo',512),
    ('elite-grid17',native/'small-grid-s1rii-p1040758-elite-grid17','prepared','grid',4913),
    ('elite-landscape-grid17',native/'small-grid-s1rii-p1176257-elite-grid17','prepared','grid',4913),
    ('elite-s9-grid17',native/'small-grid-s9-p1035087-elite-grid17','prepared','grid',4913),
):
    iface=Path(str(prefix)+f'.{tag}.actual-interface.bin').read_bytes()
    matrix=struct.unpack_from('<9f',iface,0x17b0)
    ev=struct.unpack_from('<f',iface,0x1714)[0]
    highlight=struct.unpack_from('<f',iface,0x1710)[0]
    grid=np.fromfile(str(prefix)+f'.{tag}.interface-storage-grid.bin','<f4').reshape(12,91,17,4).transpose(2,1,0,3)[...,:3].copy()
    post=np.fromfile(str(prefix)+f'.{tag}.raw-one-or-three-6144-curve-0.f32le.bin','<f4')
    if kind=='photo':
        suffixes=('.boundary.actual-input-512.q15.bin','.boundary.sensor-only-output-512.q15.bin','.boundary.actual-output-512.q15.bin')
    else:
        suffixes=('.grid-input.q15.bin','.grid-sensor-output.q15.bin','.grid-full-output.q15.bin')
    arrays=[np.fromfile(str(prefix)+s,'<i2').reshape(-1,3)[:count].copy() for s in suffixes]
    payload=b'CXORAW01'+struct.pack('<4I11f',count,grid.size,post.size,3,*matrix,ev,highlight)
    payload+=grid.astype('<f4').tobytes()+post.astype('<f4').tobytes()+b''.join(a.tobytes() for a in arrays)
    (out/(name+'.golden')).write_bytes(payload)
    print(name,len(payload),'bytes')
