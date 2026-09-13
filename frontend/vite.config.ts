import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';

// The dev server proxies the backend's paths so the session and CSRF cookies
// work under one origin during local development, with no CORS setup. In
// production the backend serves this app's built files itself (see the root
// Dockerfile), so the browser again sees a single origin.
export default defineConfig(({ mode }) => {
  // .env files only reach import.meta.env by default; loadEnv makes
  // VITE_BACKEND_ORIGIN from frontend/.env visible to this config too.
  const env = loadEnv(mode, process.cwd(), '');
  const backendOrigin = env.VITE_BACKEND_ORIGIN || 'http://localhost:8080';

  return {
    plugins: [react()],
    define: {
      __APP_VERSION__: JSON.stringify(process.env.npm_package_version || '0.0.0'),
    },
    server: {
      port: 5173,
      proxy: {
        '/api': { target: backendOrigin, changeOrigin: true },
        // Full-page navigations into Spring Security's OAuth2 flow.
        '/oauth2': { target: backendOrigin, changeOrigin: true },
        // Only the OAuth callback. Plain /login is the React sign-in route and
        // must stay on the dev server, or refreshing it would load Spring's page.
        '/login/oauth2': { target: backendOrigin, changeOrigin: true },
      },
    },
    build: {
      target: 'es2020',
      sourcemap: false,
      rollupOptions: {
        output: {
          manualChunks: {
            react: ['react', 'react-dom', 'react-router-dom'],
          },
        },
      },
    },
  };
});
