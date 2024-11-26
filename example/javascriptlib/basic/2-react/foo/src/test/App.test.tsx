import React from 'react';
import {render, screen} from '@testing-library/react';
import App from 'app';


beforeAll(() => {
    jest.spyOn(console, 'warn').mockImplementation(() => {
    });
    jest.spyOn(console, 'error').mockImplementation(() => {
    });
});

afterAll(() => {
    console.warn.mockRestore();
    console.error.mockRestore();
});

test('renders learn react link', () => {
    render(<App/>);

    const linkElement = screen.getByText(/learn react/i);
    expect(linkElement).toBeInTheDocument();
});
