#!/usr/bin/env bash
# Instala La Nuestra en un servidor Ubuntu 24.04 limpio (AWS Lightsail o EC2).
# Uso, conectado por SSH como el usuario "ubuntu":
#   git clone https://github.com/JoseEnamorado23/laNuestra.git ~/laNuestra
#   bash ~/laNuestra/deploy/instalar.sh            # usa una dirección gratis tipo 3-85-12-40.sslip.io
#   bash ~/laNuestra/deploy/instalar.sh pedidos.midominio.co   # o tu dominio, ya apuntando a la IP
set -euo pipefail
APP_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$APP_DIR"

IP="$(curl -fsS https://checkip.amazonaws.com | tr -d '[:space:]')"
DOMINIO="${1:-${IP//./-}.sslip.io}"
echo "==> Instalando en $APP_DIR para https://$DOMINIO"

# Memoria de intercambio: los planes pequeños se quedan cortos al instalar paquetes.
if ! swapon --show | grep -q /swapfile; then
  sudo fallocate -l 1G /swapfile && sudo chmod 600 /swapfile && sudo mkswap /swapfile >/dev/null && sudo swapon /swapfile
  echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab >/dev/null
fi

echo "==> Paquetes del sistema, Node.js 24 y Caddy"
sudo apt-get update -y
sudo apt-get install -y curl git ca-certificates gnupg debian-keyring debian-archive-keyring apt-transport-https
if ! node -v 2>/dev/null | grep -q '^v24'; then
  curl -fsSL https://deb.nodesource.com/setup_24.x | sudo -E bash -
  sudo apt-get install -y nodejs
fi
if ! command -v caddy >/dev/null; then
  curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' | sudo gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
  curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' | sudo tee /etc/apt/sources.list.d/caddy-stable.list >/dev/null
  sudo apt-get update -y && sudo apt-get install -y caddy
fi
command -v pm2 >/dev/null || sudo npm install -g pm2

echo "==> Dependencias de la app"
npm ci --omit=dev

if [ ! -f .env ]; then
  cp .env.example .env
  sed -i "s|^PORT=.*|PORT=3001|; s|^PUBLIC_URL=.*|PUBLIC_URL=https://$DOMINIO|" .env
  echo "==> Se creó .env. Falta poner las claves (ANTHROPIC_API_KEY, TWILIO_*):  nano $APP_DIR/.env"
fi
PORT="$(grep -E '^PORT=' .env | cut -d= -f2)"; PORT="${PORT:-3001}"

if [ ! -f data/lanuestra.db ]; then
  echo "==> Base nueva: cargando datos de muestra"
  npm run demo
fi

echo "==> Arrancando con pm2"
pm2 startOrReload ecosystem.config.cjs
pm2 save
sudo env PATH="$PATH" pm2 startup systemd -u "$USER" --hp "$HOME" >/dev/null

echo "==> HTTPS con Caddy"
sudo tee /etc/caddy/Caddyfile >/dev/null <<CADDY
$DOMINIO {
  encode gzip
  reverse_proxy localhost:$PORT
}
CADDY
sudo systemctl reload caddy || sudo systemctl restart caddy

echo "==> Respaldo diario de la base (3:30 a.m., se guardan 14 días)"
( crontab -l 2>/dev/null | grep -v 'deploy/respaldo.sh'; echo "30 8 * * * bash $APP_DIR/deploy/respaldo.sh >> $APP_DIR/respaldos.log 2>&1" ) | crontab -

cat <<FIN

Listo.
  App y panel:      https://$DOMINIO
  Webhook Twilio:   https://$DOMINIO/twilio/webhook
  En la app Android, en "Servidor", escribe:  https://$DOMINIO
  Ver registros:    pm2 logs la-nuestra
Si acabas de editar .env:  pm2 reload la-nuestra
FIN
