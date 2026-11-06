# Runtime dependency license review

Reviewed 2026-11-06 for the resolved `:app:previewRuntimeClasspath` report. This is an engineering inventory, not legal advice.

- 125 resolved coordinate rows are recorded in `dependency-license-review.csv`; this graph includes metadata-only BOMs and multiplatform aliases, not 125 distinct packaged libraries.
- 79 coordinate rows had cached local POM license metadata.
- For the 46 coordinates without cached local POM metadata, 45 official repository POMs were retrieved and declare Apache License 2.0. Their URLs and SHA256 values are in `dependency-pom-evidence.json`.
- `com.google.guava:listenablefuture:1.0` has no license element in its POM. The official Maven source archive was inspected; `ListenableFuture.java` has an Apache License 2.0 header. Archive and source-file hashes are retained in the same evidence file.
- All 125 rows are identified as Apache-2.0 from POM declarations or the source header. The full license text is included in `THIRD_PARTY_NOTICES/Apache-2.0.txt`; the app stages the text and CSV inventory into APK assets.
- Separately vendored components retain their own notices. LibRaw source distribution remains required by the selected license; the archived 225-file source bundle matches the current vendor tree in the audit evidence.

Remaining boundary: the resolved graph is not a binary content inventory. Before public distribution, inspect the final APK's packaged classes/native components against this graph and preserve any upstream NOTICE files found in those artifacts. The count must not be presented as a count of embedded libraries.
