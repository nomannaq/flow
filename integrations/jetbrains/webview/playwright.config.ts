import { defineConfig } from '@playwright/test';
export default defineConfig({
  testDir: './tests',
  timeout: 20000,
  workers: 2,
  use: {
    browserName: 'chromium',
    viewport: { width: 420, height: 780 },
    screenshot: 'only-on-failure',
  },
  reporter: [['list']],
});
