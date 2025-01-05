import { test, expect } from '@playwright/test';

const URL = "http://localhost:3000";

test.describe('Fibonacci endpoint', () => {
  test('returns correct fibonacci numbers', async ({ request }) => {
    const testCases = [
      { n: 0, expected: 0 },
      { n: 1, expected: 1 },
      { n: 5, expected: 5 },
      { n: 10, expected: 55 }
    ];

    for (const { n, expected } of testCases) {
      const response = await request.get(`${URL}/fibonacci?n=${n}`);
      const body = await response.json();
      expect(response.ok()).toBeTruthy();
      expect(body.result).toBe(expected);
    }
  });

  test('handles invalid input', async ({ request }) => {
    const testCases = [
      { input: 'abc', expectedError: 'Please provide a valid number' },
      { input: '-1', expectedError: 'Please provide a non-negative number' },
      { input: '101', expectedError: 'Please provide a number less than or equal to 100' }
    ];

    for (const { input, expectedError } of testCases) {
      const response = await request.get(`${URL}/fibonacci?n=${input}`);
      const body = await response.json();
      expect(response.status()).toBe(400);
      expect(body.error).toBe(expectedError);
    }
  });
});