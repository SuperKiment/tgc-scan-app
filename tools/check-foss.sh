#!/usr/bin/env bash
# FOSS and permission audit (AC-2, AC-3, D5). Run from anywhere; exits non-zero on any violation.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

ALLOWED_PERMS=(
  android.permission.CAMERA
  android.permission.INTERNET
  android.permission.ACCESS_NETWORK_STATE
  # androidx.core self-declared signature permission (not a user permission; cannot be removed)
  org.tcgscan.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION
)
failures=0

echo "== AC-2: proprietary Google dependencies =="
deps="$(./gradlew :app:dependencies --configuration releaseRuntimeClasspath --console=plain)"
if hits="$(grep -E 'com\.google\.android\.gms|com\.google\.mlkit|com\.google\.firebase' <<<"$deps")"; then
  echo "FAIL AC-2: forbidden dependencies found:"; echo "$hits"; failures=$((failures + 1))
else
  echo "PASS AC-2: no gms / mlkit / firebase in releaseRuntimeClasspath"
fi

echo "== AC-3 / D5: merged release manifest =="
./gradlew :app:processReleaseManifest --console=plain >/dev/null

# AGP 8.7 writes the merged manifest below a task-named subdirectory (and per-ABI copies under
# merged_manifests/); audit every release copy. The paths are expanded only here, *after* the task
# above has run: on a fresh clone app/build/ does not exist yet, so expanding them earlier would
# leave the literal glob behind and fail the audit for the wrong reason.
shopt -s nullglob
MANIFESTS=()
for CANDIDATE in \
  app/build/intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml \
  app/build/intermediates/merged_manifests/release/processReleaseManifest/*/AndroidManifest.xml
do
  # The first candidate has no wildcard, so nullglob cannot drop it when it is missing.
  if [ -f "$CANDIDATE" ]; then MANIFESTS+=("$CANDIDATE"); fi
done
shopt -u nullglob

if [ "${#MANIFESTS[@]}" -eq 0 ]; then
  echo "FAIL AC-3: no merged release manifest under app/build/intermediates/ after processReleaseManifest"
  exit 1
fi

for MANIFEST in "${MANIFESTS[@]}"; do
echo "-- $MANIFEST"

bad_perms=0
while IFS= read -r perm; do
  ok=0
  for a in "${ALLOWED_PERMS[@]}"; do [ "$perm" = "$a" ] && ok=1; done
  if [ "$ok" -eq 0 ]; then echo "FAIL AC-3: unexpected uses-permission: $perm"; bad_perms=1; fi
done < <(tr '\n' ' ' <"$MANIFEST" | grep -oE '<uses-permission(-sdk-23)?[^>]*>' | grep -oE 'android:name="[^"]+"' | sed -E 's/android:name="([^"]+)"/\1/' | sort -u)
if [ "$bad_perms" -eq 0 ]; then echo "PASS AC-3: only CAMERA, INTERNET, ACCESS_NETWORK_STATE requested"; else failures=$((failures + 1)); fi

if grep -q 'ai\.onnxruntime\.TelemetryInitializer' "$MANIFEST"; then
  echo "FAIL D5: ai.onnxruntime.TelemetryInitializer present in merged manifest"; failures=$((failures + 1))
else
  echo "PASS D5: ONNX Runtime telemetry provider removed"
fi
done

echo "== Summary =="
if [ "$failures" -eq 0 ]; then echo "FOSS audit: PASS"; else echo "FOSS audit: FAIL ($failures check(s))"; exit 1; fi
