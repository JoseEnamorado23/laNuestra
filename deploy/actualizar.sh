#!/usr/bin/env bash
# Trae los últimos cambios de GitHub y reinicia sin perder datos.
#   bash ~/laNuestra/deploy/actualizar.sh
set -euo pipefail
cd "$(dirname "$0")/.."
bash deploy/respaldo.sh
git pull --ff-only
npm ci --omit=dev
pm2 reload la-nuestra
echo "Actualizado: $(git log -1 --format='%h %s')"
