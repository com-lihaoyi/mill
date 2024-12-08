// import React from 'react';
// import {render, screen, fireEvent, waitFor} from '@testing-library/react';
// import App from '../app/App';
//
// describe('App Component', () => {
//     beforeEach(() => {
//         jest.spyOn(global, 'fetch');
//         jest.spyOn(console, 'warn').mockImplementation(() => {});
//         jest.spyOn(console, 'error').mockImplementation(() => {});
//     });
//
//     afterEach(() => {
//         jest.restoreAllMocks();
//     });
//
//     test('renders todos fetched from server', async () => {
//         const mockTodos = [{id: 1, text: 'Learn React'}];
//         (global.fetch as jest.Mock).mockResolvedValueOnce({
//             json: async () => mockTodos,
//             ok: true,
//         });
//
//         render(<App/>);
//
//         await waitFor(() => {
//             expect(screen.getByText('Learn React')).toBeInTheDocument();
//         });
//
//         expect(global.fetch).toHaveBeenCalledWith('http://localhost:3001/api/todos');
//     });
//
//     test('adds a new todo item', async () => {
//         const mockTodos = [{id: 1, text: 'Learn React'}];
//         const newTodo = {id: 2, text: 'Test new todo'};
//
//         // Mock fetch responses
//         (global.fetch as jest.Mock)
//             // Mock initial GET request
//             .mockResolvedValueOnce({
//                 ok: true,
//                 json: async () => mockTodos,
//             })
//             // Mock POST request
//             .mockResolvedValueOnce({
//                 ok: true,
//                 json: async () => newTodo,
//             });
//
//         render(<App/>);
//
//         // Wait for the initial todos to load
//         await waitFor(() => {
//             expect(screen.getByText('Learn React')).toBeInTheDocument();
//         });
//
//         // Simulate adding a new todo
//         const input = screen.getByRole('textbox');
//         const button = screen.getByRole('button', {name: /Add Todo/i});
//
//         // Type into the input field
//         fireEvent.change(input, {target: {value: 'Test new todo'}});
//
//         // Click the "Add Todo" button
//         fireEvent.click(button);
//         // Wait for the state update and re-render
//         await waitFor(() => {
//             expect(screen.getByText('Test new todo')).toBeInTheDocument();
//         });
//
//         // Assert fetch was called with correct arguments
//         expect(global.fetch).toHaveBeenCalledWith(
//             'http://localhost:3001/api/todos',
//             expect.objectContaining({
//                 method: 'POST',
//                 headers: {'Content-Type': 'application/json'},
//                 body: JSON.stringify({text: 'Test new todo'}),
//             })
//         );
//     });
//
//
//     test('does not add a todo if input is empty', async () => {
//         const mockTodos = [{id: 1, text: 'Learn React'}];
//
//         (global.fetch as jest.Mock).mockResolvedValueOnce({
//             json: async () => mockTodos,
//             ok: true,
//         });
//
//         render(<App/>);
//
//         await waitFor(() => {
//             expect(screen.getByText('Learn React')).toBeInTheDocument();
//         });
//
//         const button = screen.getByRole('button', {name: /add todo/i});
//         fireEvent.click(button);
//
//         expect(global.fetch).toHaveBeenCalledTimes(1); // Only the initial GET request
//         expect(screen.queryAllByRole('listitem')).toHaveLength(1); // Only the initial todo
//     });
// });

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