# tools/epk_tool.py — wrap the APK for the App Garage USB loader

The head unit's `AppManager` only loads apps from USB when they're wrapped in its `.epk` container.
`epk_tool.py` reimplements that format so a normal APK can be wrapped into an `.epk` the unit will
decrypt and install.

```
python tools/epk_tool.py build build/dash.apk build/dash.epk --cert keys/obu_cert.pem
python tools/epk_tool.py parse dash.epk out/ --key keys/obu_key.pem     # verify
python tools/epk_tool.py selftest                                       # round-trip test
```
Requires `cryptography` (`pip install cryptography`).

## Keys

The `.epk` container is **encryption-only**. Building one needs the **public** OBU certificate,
which is included at [`keys/obu_cert.pem`](../keys/README.md) — so `build` works out of the box.
The **private** key is *not* included (it isn't needed to build); without it, `parse` and `selftest`
won't run, but `build` does. No firmware or p12 password is distributed.

If your head unit runs different firmware, replace `keys/obu_cert.pem` with the cert from your own
unit (see [`keys/README.md`](../keys/README.md)). This tooling is for loading software onto
**a vehicle you own**.
