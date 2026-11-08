#!/usr/bin/env bash
#
# Fetch the ML model assets shipped inside the APK (docs/models.md, plan decisions D6/D7/D8).
#
# Every asset is pinned in tools/models.lock as "<asset-name> <sha256> <bytes> <url>".
# A file whose size and sha256 already match the lock is left untouched; everything else is
# downloaded into app/src/main/assets/models/ through a temporary ".part" file and only moved
# into place once both the byte count and the sha256 match.
#
# The script fails loudly - and the build with it - on a download error (404, network) or on a
# checksum mismatch: OCR and embedding are never stubbed out. Model files are data only; they
# are stored without the executable bit and are never run by this script.
#
# Usage: bash tools/fetch_models.sh
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/.." && pwd)"
LOCK_FILE="${SCRIPT_DIR}/models.lock"
DEST_DIR="${REPO_ROOT}/app/src/main/assets/models"

# Temporary file of the download in flight, removed by the EXIT/INT/TERM trap.
PART_FILE=""

cleanup() {
  if [[ -n "${PART_FILE}" && -e "${PART_FILE}" ]]; then
    rm -f -- "${PART_FILE}"
  fi
}
trap cleanup EXIT INT TERM

log() {
  printf '%s\n' "$*"
}

print_mirrors() {
  cat >&2 <<'EOF'

Mirrors (same files, same Apache-2.0 licence) if an upstream repository vanished - update
tools/models.lock AND docs/models.md together, never only one of them:
  embed_dinov2s_int8.onnx : Xenova/dinov2-small           -> onnx/model_quantized.onnx
  ocr_rec_latin.onnx      : itextresearch/itext-latin_PP-OCRv3_mobile_rec_infer -> inference.onnx
  ocr_rec_japan.onnx      : itextresearch/itext-japan_PP-OCRv3_mobile_rec_infer -> inference.onnx
                            cycloneboy/japan_PP-OCRv4_rec_infer
  ocr_det.onnx            : any PP-OCRv4 ch_PP-OCRv4_det_infer.onnx export
  dict_latin.txt          : PaddleOCR ppocr/utils/dict/latin_dict.txt (any release tag >= 2.7)
  dict_japan.txt          : PaddleOCR ppocr/utils/dict/japan_dict.txt (any release tag >= 2.7)
EOF
}

die() {
  printf 'ERROR: %s\n' "$*" >&2
  print_mirrors
  exit 1
}

file_sha256() {
  sha256sum -- "$1" | cut -d ' ' -f 1
}

file_bytes() {
  stat -c '%s' -- "$1"
}

# matches <path> <sha256> <bytes>: true when the file exists with the expected size and digest.
matches() {
  local path="$1" want_sha="$2" want_bytes="$3"
  [[ -f "${path}" ]] || return 1
  [[ "$(file_bytes "${path}")" == "${want_bytes}" ]] || return 1
  [[ "$(file_sha256 "${path}")" == "${want_sha}" ]] || return 1
}

for tool in curl sha256sum stat; do
  command -v "${tool}" >/dev/null 2>&1 || die "'${tool}' is required but was not found in PATH."
done

[[ -f "${LOCK_FILE}" ]] || die "lock file not found: ${LOCK_FILE}"

mkdir -p -- "${DEST_DIR}"

fetched=0
skipped=0
total=0

while read -r name sha bytes url extra; do
  # Skip blank lines and comments.
  [[ -z "${name}" || "${name}" == '#'* ]] && continue
  [[ -n "${sha}" && -n "${bytes}" && -n "${url}" && -z "${extra}" ]] ||
    die "malformed entry in ${LOCK_FILE}: expected '<asset-name> <sha256> <bytes> <url>', got '${name} ${sha} ${bytes} ${url} ${extra}'"
  [[ "${name}" != */* ]] || die "asset name must not contain a path separator: '${name}'"
  [[ "${sha}" =~ ^[0-9a-f]{64}$ ]] || die "${name}: '${sha}' is not a sha256 digest"
  [[ "${bytes}" =~ ^[0-9]+$ ]] || die "${name}: '${bytes}' is not a byte count"

  total=$((total + 1))
  target="${DEST_DIR}/${name}"

  if matches "${target}" "${sha}" "${bytes}"; then
    log "ok      ${name} (already present, sha256 verified)"
    skipped=$((skipped + 1))
    continue
  fi

  if [[ -f "${target}" ]]; then
    log "stale   ${name} (size or sha256 differs from the lock, re-downloading)"
  fi

  log "fetch   ${name} <- ${url}"
  PART_FILE="${target}.part"
  rm -f -- "${PART_FILE}"
  curl -fL --retry 3 --retry-delay 2 --connect-timeout 20 --progress-bar \
    -o "${PART_FILE}" -- "${url}" ||
    die "${name}: download failed (curl exit $?) from ${url}"

  got_bytes="$(file_bytes "${PART_FILE}")"
  if [[ "${got_bytes}" != "${bytes}" ]]; then
    die "${name}: size mismatch - expected ${bytes} bytes, got ${got_bytes} from ${url}"
  fi
  got_sha="$(file_sha256 "${PART_FILE}")"
  if [[ "${got_sha}" != "${sha}" ]]; then
    die "${name}: sha256 mismatch - expected ${sha}, got ${got_sha} from ${url}"
  fi

  chmod 0644 -- "${PART_FILE}"
  mv -- "${PART_FILE}" "${target}"
  PART_FILE=""
  log "ok      ${name} (${bytes} bytes, sha256 verified)"
  fetched=$((fetched + 1))
done < "${LOCK_FILE}"

[[ "${total}" -gt 0 ]] || die "no asset entry found in ${LOCK_FILE}"

log ""
log "${total} model asset(s) in ${DEST_DIR}: ${fetched} downloaded, ${skipped} already verified."
