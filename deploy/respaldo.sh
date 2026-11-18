#!/usr/bin/env bash
# Copia consistente de la base SQLite (aunque el servidor esté funcionando). Guarda 14 días.
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p respaldos
DESTINO="respaldos/lanuestra-$(date +%Y%m%d-%H%M).db"
node -e '
const { DatabaseSync } = require("node:sqlite");
const db = new DatabaseSync(process.argv[1]);
db.exec("VACUUM INTO " + JSON.stringify(process.argv[2]).replace(/"/g, "'"'"'"));
' "${DB_ARCHIVO:-data/lanuestra.db}" "$DESTINO"
ls -1t respaldos/lanuestra-*.db | tail -n +15 | xargs -r rm --
echo "Respaldo: $DESTINO"
