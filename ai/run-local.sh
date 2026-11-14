#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
if [[ -f ai/.env.local ]]; then
  set -a
  source ai/.env.local
  set +a
fi
: "${GUARDWORK_AI_LEGAL_SNAPSHOT:?Set GUARDWORK_AI_LEGAL_SNAPSHOT to the imported legal corpus identifier; use the same value in the backend}"
if [[ "$GUARDWORK_AI_LEGAL_SNAPSHOT" == "stub-v1" || "$GUARDWORK_AI_LEGAL_SNAPSHOT" == "replace-with-imported-corpus-id" ]]; then
  echo 'Real analysis requires an actual corpus identifier, not stub-v1.' >&2
  exit 1
fi
export GUARDWORK_AI_ENABLED=true GUARDWORK_AI_MODE=crawler
if [[ "${1:-adapter}" == "backend" ]]; then
  cd backend
  exec ./mvnw spring-boot:run
fi
if [[ "${1:-adapter}" != "adapter" && "${1:-adapter}" != "check" ]]; then
  echo 'Usage: bash ai/run-local.sh [adapter|backend|check]' >&2
  exit 1
fi
check_args=()
if [[ "${1:-adapter}" == "check" ]]; then check_args=(--check); fi
crawler_python="$(cd ../crawler && pwd)/.venv/bin/python"
exec "$crawler_python" -B -m ai.jd_adapter --mode crawler \
  --snapshot "$GUARDWORK_AI_LEGAL_SNAPSHOT" --profile "${GUARDWORK_AI_PROFILE:-jd-local-v1}" "${check_args[@]}"
