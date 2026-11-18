// Configuración de pm2 (mantiene el servidor encendido y lo reinicia si se cae).
//   pm2 start ecosystem.config.cjs
module.exports = {
  apps: [
    {
      name: "la-nuestra",
      script: "src/server.js",
      node_args: "--env-file-if-exists=.env",
      cwd: __dirname,
      max_memory_restart: "300M",
      time: true,
    },
  ],
};
