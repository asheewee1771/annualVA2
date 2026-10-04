import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import basicSsl from "@vitejs/plugin-basic-ssl";

// The backend serves HTTPS on 8443 with a self-signed dev certificate. The dev server proxies every
// backend path so the session cookie and the OIDC redirects stay on https://localhost:5173.
const backend = { target: "https://localhost:8443", secure: false, changeOrigin: false, xfwd: true };

export default defineConfig({
  plugins: [react(), basicSsl()],
  server: {
    port: 5173,
    strictPort: true,
    proxy: {
      "/api": backend,
      "/oauth2": backend,
      "/login": backend,
      "/logout": backend,
      "/auth": backend,
      "/.well-known": backend,
      "/actuator": backend,
    },
  },
  build: {
    sourcemap: false,
  },
});
