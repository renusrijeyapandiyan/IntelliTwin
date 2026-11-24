import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// Dev: /api is proxied to the Spring Boot backend. Production: nginx does the same (see nginx.conf).
export default defineConfig({
  plugins: [react()],
  server: { port: 5173, proxy: { '/api': { target: process.env.VITE_BACKEND || 'http://localhost:8080', changeOrigin: true } } },
})
