import { test, expect } from 'node_modules/@playwright/test';

test.describe('React App', () => {
    test('displays the heading', async ({ page }) => {
        // Visit the base URL
        await page.goto('/');

        // Check if the heading is visible
        const heading = page.locator('[data-testid="heading"]');
        await expect(heading).toBeVisible();
        await expect(heading).toHaveText('Hello, Cypress & PlayWright');
    });
});