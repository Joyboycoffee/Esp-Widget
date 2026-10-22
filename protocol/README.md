# Shared protocol fixtures

`vectors.json` is the single source of truth for the Android and firmware protocol tests. It contains complete frames, the CRC reference value and a fragmentation example.

Regenerate both this JSON and the firmware C++ header:

```bash
python tools/make_vectors.py
```

The firmware's native test target includes `test/vectors_generated.h`; the Android unit tests load `vectors.json` from test resources (`app/app/build.gradle.kts` adds this directory as a test resource root).

The full protocol reference — frame layout, CRC, BLE UUIDs, fragmentation, message table, layout and photo flow — is in [../docs/PROTOCOL.md](../docs/PROTOCOL.md).
