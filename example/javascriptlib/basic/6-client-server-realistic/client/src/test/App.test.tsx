import React from 'react';
import {render, screen, fireEvent, waitFor} from '@testing-library/react';
import App from '../app/App';

global.fetch = jest.fn();

beforeEach(() => {
    jest.clearAllMocks();
});

test('renders initial todos from server', async () => {
    (global.fetch as jest.Mock).mockResolvedValueOnce({
        json: async () => [{id: 1, text: 'Learn React', checked: false}],
        ok: true,
    });

    render(<App/>);

    await waitFor(() => {
        expect(screen.getByText('Learn React')).toBeInTheDocument();
    });
});

test('adds a new todo', async () => {
    (global.fetch as jest.Mock)
        .mockResolvedValueOnce({
            json: async () => [],
            ok: true,
        }) // Initial GET
        .mockResolvedValueOnce({
            json: async () => ({id: 2, text: 'Write tests', checked: false}),
            ok: true,
        }); // POST

    render(<App/>);

    const input = screen.getByPlaceholderText('What needs to be done?');
    fireEvent.change(input, {target: {value: 'Write tests'}});
    fireEvent.keyDown(input, {key: 'Enter', code: 'Enter'});

    await waitFor(() => {
        expect(screen.getByText('Write tests')).toBeInTheDocument();
    });
});

test('toggles a todo', async () => {
    (global.fetch as jest.Mock)
        .mockResolvedValueOnce({
            json: async () => [{id: 1, text: 'Learn React', checked: false}],
            ok: true,
        }) // Initial GET
        .mockResolvedValueOnce({
            json: async () => ({id: 1, text: 'Learn React', checked: true}),
            ok: true,
        }); // POST toggle

    render(<App/>);

    await waitFor(() => {
        expect(screen.getByText('Learn React')).toBeInTheDocument();
    });

    const checkboxes = screen.getAllByRole('checkbox');
    const checkbox = checkboxes.find((checkbox) =>
        checkbox.classList.contains('toggle')
    );

    fireEvent.click(checkbox);

    await waitFor(() => {
        expect(checkbox).toBeChecked();
    });
});