// @ts-nocheck
import { defineConfig } from 'cypress';
const port = process.env.PORT
export default defineConfig({
  e2e: {
    specPattern: '**/e2e/*.cy.ts',
    baseUrl: `http://localhost:${port}`,
    supportFile: false
  }
});
