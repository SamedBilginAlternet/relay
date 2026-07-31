import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Backend (Spring Boot) is expected on :8080 during local development.
// Docker build args may arrive as EMPTY strings — always fall back with `||`.
const apiTarget = process.env.VITE_DEV_API_TARGET || 'http://localhost:8080';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    host: true,
    proxy: {
      '/api': {
        target: apiTarget,
        changeOrigin: true,
        // SSE must not be buffered
        configure: (proxy) => {
          proxy.on('proxyRes', (proxyRes) => {
            if (proxyRes.headers['content-type']?.includes('text/event-stream')) {
              delete proxyRes.headers['content-length'];
            }
          });
        },
      },
    },
  },
  build: {
    outDir: 'dist',
    sourcemap: false,
  },
});
