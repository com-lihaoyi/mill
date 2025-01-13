// @ts-nocheck
import { defineConfig } from 'node_modules/cypress';

export default defineConfig({
    e2e: {
        specPattern: '**/e2e/*.cy.ts',
        baseUrl: 'http://localhost:4000',
        supportFile: false
    }
});