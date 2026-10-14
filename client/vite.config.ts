import react from '@vitejs/plugin-react'
import { defineConfig, loadEnv } from 'vite'

// https://vite.dev/config/
// In development the API is proxied to the Spring Boot backend, so the browser talks to
// one origin. Override the target with VITE_BACKEND_URL (default http://localhost:8080).
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  return {
    plugins: [react()],
    server: {
      // When testing OAuth through an HTTPS tunnel, allow only that exact public host.
      // The browser must use the same hostname for the app and OAuth callback so the
      // callback-binding cookie remains available to the backend.
      allowedHosts: env.VITE_DEV_ALLOWED_HOST ? [env.VITE_DEV_ALLOWED_HOST] : undefined,
      proxy: {
        '/connect-ai': {
          target: env.VITE_BACKEND_URL || 'http://localhost:8080',
          changeOrigin: true,
        },
      },
    },
  }
})
