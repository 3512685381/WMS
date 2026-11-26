import tailwindcss from '@tailwindcss/vite'
import vue from '@vitejs/plugin-vue'
import { defineConfig, loadEnv } from 'vite'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), 'WMS_')
  const proxy = {
    '/api': { target: env.WMS_API_TARGET || 'http://127.0.0.1:8080', changeOrigin: true },
    '/ai': { target: env.WMS_AI_TARGET || 'http://127.0.0.1:8000', changeOrigin: true },
  }

  return {
    plugins: [vue(), tailwindcss()],
    server: { host: '127.0.0.1', port: Number(env.WMS_WEB_PORT || 5173), strictPort: true, proxy },
    preview: { host: '127.0.0.1', proxy },
  }
})
