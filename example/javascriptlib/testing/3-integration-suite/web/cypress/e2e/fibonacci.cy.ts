const URL = "http://localhost:3000";

describe('Fibonacci API', () => {
  it('returns correct fibonacci numbers', () => {
    const testCases = [
      { n: 0, expected: 0 },
      { n: 1, expected: 1 },
      { n: 5, expected: 5 },
      { n: 10, expected: 55 }
    ];

    testCases.forEach(({ n, expected }) => {
      cy.request(`${URL}/fibonacci?n=${n}`).then((response) => {
        expect(response.status).to.eq(200);
        expect(response.body.result).to.eq(expected);
      });
    });
  });

  it('handles invalid input', () => {
    const testCases = [
      { input: 'abc', expectedError: 'Please provide a valid number' },
      { input: '-1', expectedError: 'Please provide a non-negative number' },
      { input: '101', expectedError: 'Please provide a number less than or equal to 100' }
    ];

    testCases.forEach(({ input, expectedError }) => {
      cy.request({
        url: `${URL}/fibonacci?n=${input}`,
        failOnStatusCode: false
      }).then((response) => {
        expect(response.status).to.eq(400);
        expect(response.body.error).to.eq(expectedError);
      });
    });
  });
});