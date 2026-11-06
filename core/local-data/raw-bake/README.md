# CXORBAKE1 fixture

The three `*.cxorbake` files are little-endian research fixtures, not a DxO
file format. Each file has this layout:

1. 9-byte magic `CXORBAKE1`
2. UInt32 record count (`1`)
3. `ev`, `renderingEv` as Float32
4. 27 Float32 values: `preMatrix`, `postMatrix`, `extraMatrix` (row-major)
5. one 32769-sample shared film curve
6. one 32769-sample shared composed-inverse curve
7. expected pre-repair grid, packed `S17,H91,V12,C3`
8. expected repaired grid, packed `S17,H91,V12,C3`

Each file's parameters and expected grids were decoded from its matching
captured native evidence (`P1040758`, `P1176257`, or `P1035087`). The native
storage was transposed from `V,H,S,C4` and its fourth component was omitted.

The fixtures cover S1RII P1040758, S1RII P1176257, and S9 P1035087. They
verify the shared path against native data. The verification also runs the
independent three-channel curve path with equivalent-size tables; a separate
native three-channel capture is still required before claiming branch-specific
DxO parity.
