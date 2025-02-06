import React from 'react';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import '@testing-library/jest-dom'; // Import jest-dom matchers
import App from 'app/App';

// Mock the fetch API
global.fetch = jest.fn();

describe('Text Analysis Tool', () => {
    beforeEach(() => {
        (fetch as jest.Mock).mockClear();
    });

    test('renders the app with initial UI', () => {
        render(<App />);

        // Check for the page title
        expect(screen.getByText('Text Analysis Tool')).toBeInTheDocument();

        // Check for the input form
        expect(screen.getByPlaceholderText('Enter your text here...')).toBeInTheDocument();
        expect(screen.getByText('Analyze')).toBeInTheDocument();
    });

    test('displays sentiment result', async () => {
        // Mock the fetch response for positive sentiment
        (fetch as jest.Mock).mockResolvedValueOnce({
            ok: true,
            text: async () => 'Positive sentiment (polarity: 0.8)',
        });

        render(<App />);

        // Simulate user input and form submission
        fireEvent.change(screen.getByPlaceholderText('Enter your text here...'), {
            target: { value: 'This is amazing!' },
        });
        fireEvent.click(screen.getByText('Analyze'));

        // Wait for the result to appear
        await waitFor(() => screen.getByText('Analysis Result:'));

        // Check that the result is displayed
        expect(screen.getByText('Positive sentiment (polarity: 0.8)')).toBeInTheDocument();
        expect(screen.getByText('Analysis Result:').parentElement).toHaveClass('positive');
    });
});