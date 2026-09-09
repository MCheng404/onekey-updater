import { defineConfig } from 'vite';

const host = process.env.TAURI_DEV_HOST;

export default defineConfig({
  clearScreen: false,
  server: {
    port: 1420,
    strictPort: true,
    host: host || false,
    hmr: host ? { protocol: 'ws', host, port: 1421 } : undefined,
    watch: { ignored: ['**/src-tauri/**'] },
  },
  build: {
    target: 'esnext',
    outDir: 'dist',
    minify: 'esbuild',
    sourcemap: false,
    chunkSizeWarningLimit: 2000,
    // 多页面：主窗口 + 设置子窗口 + 通知窗口 + 关于窗口
    rollupOptions: {
      input: {
        main: 'index.html',
        settings: 'settings.html',
        notify: 'notify.html',
        about: 'about.html',
      },
    },
  },
});
