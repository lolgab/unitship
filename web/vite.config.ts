import { defineConfig } from 'vite'
import preact from '@preact/preset-vite'

// `npm run dev` serves the page with hot reload and sends /api to a running panel (PANEL_URL).
export default defineConfig({
  plugins: [preact()],
  server: { proxy: { '/api': process.env.PANEL_URL ?? 'http://127.0.0.1:8081' } },
  build: { outDir: 'dist', assetsDir: 'assets', sourcemap: false, target: 'es2020' },
  test: { include: ['src/**/*.test.ts'] },
})
