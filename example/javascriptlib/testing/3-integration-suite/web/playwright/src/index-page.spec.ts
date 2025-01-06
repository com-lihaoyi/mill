import { test, expect } from '@playwright/test';

test('Homepage has title and counter button working', async ({ page }) => {
  await page.goto('http://localhost:3000');

  await expect(page).toHaveTitle('Javascript Integration Testing');

  const counter = page.locator('#counter');

  await expect(counter).toHaveText('0');

  // Click the button and verify the count increases
  await counter.click();
  await expect(counter).toHaveText('1');
});
