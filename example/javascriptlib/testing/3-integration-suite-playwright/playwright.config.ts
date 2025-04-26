import { defineConfig } from '@playwright/test';
import * as glob from 'glob';
import * as path from 'path';

const testFiles = glob.sync('**/playwright/*.test.ts', { absolute: true });

const port = process.env.PORT

export default defineConfig({
  testDir: './',
  testMatch: testFiles.map(file => path.relative(process.cwd(), file)),
  timeout: 30000,
  retries: 1,
  use: {
    baseURL: `http://localhost:${port}`,
    headless: true,
    trace: 'on-first-retry',
    launchOptions: {
      args: ['--explicitly-allowed-ports=6000']
    },
    channel: 'chrome', // Use the stable Chrome channel
  },
  projects: [
    {
      name: 'chromium',
      use: { browserName: 'chromium' }
    }
  ]
});
