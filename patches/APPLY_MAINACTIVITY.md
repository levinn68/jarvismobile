# Apply MainActivity lifecycle polish

Remote branch may lag the local commit that wires:
- audio focus, stop receiver, recognizer recovery
- wake hey/hai/halo Jarvis; standby/tidur/sleep
- chat clientRequestId + transient retry
- HolographicOrb binding
- ConnectivityManager auto-flush for offline queue

## Apply

```bash
git checkout feature/qr-pairing-v1
git apply patches/0663a96-MainActivity.patch
# or: cp path/to/MainActivity.kt app/src/main/java/com/jarvis/mobile/MainActivity.kt
git add app/src/main/java/com/jarvis/mobile/MainActivity.kt
git commit -m "fix(voice,chat): full lifecycle polish, connectivity auto-flush"
git push origin feature/qr-pairing-v1
```

## Verify

```bash
grep -n "requestMicFocus\|clientRequestId\|HolographicOrb\|registerConnectivityFlush" \
  app/src/main/java/com/jarvis/mobile/MainActivity.kt
```
