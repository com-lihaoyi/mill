describe('Homepage', () => {
  it('has title and counter button working', () => {
    cy.visit('/');

    cy.title().should('eq', 'Javascript Integration Testing');

    cy.get('#counter').should('have.text', '0');

    // Click the button and verify the count increases
    cy.get('#counter').click().should('have.text', '1');
  });
});