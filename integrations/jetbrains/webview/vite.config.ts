import { defineConfig } from 'vite';

export default defineConfig({
  define: { 'process.env.NODE_ENV': JSON.stringify('production') },
  build: {
    target: 'chrome120',
    outDir: '../build/react-chat',
    emptyOutDir: true,
    lib: {
      entry: 'src/main.tsx',
      name: 'FlowChat',
      formats: ['iife'],
      fileName: () => 'chat.js',
      cssFileName: 'chat',
    },
    // JCEF receives one self-contained, nonce-protected document. No dev server or CDN in production.
    cssCodeSplit: false,
  },
});
