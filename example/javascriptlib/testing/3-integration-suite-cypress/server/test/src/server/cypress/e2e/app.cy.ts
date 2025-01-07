describe('React App', () => {
    it('displays the heading', () => {
        // Visit the base URL
        cy.visit('/');

        // Check if the heading is visible and contains "Hello, Cypress!"
        cy.get('[data-testid="heading"]').should('be.visible').and('contain.text', 'Hello, Cypress & PlayWright');
    });
});