import React, {useState, useEffect} from 'react';

interface Todo {
    id: number;
    text: string;
    checked: boolean;
}

const App: React.FC = () => {
    const [todos, setTodos] = useState<Todo[]>([]);
    const [newTodoText, setNewTodoText] = useState('');
    const [filter, setFilter] = useState<'all' | 'active' | 'completed'>('all');
    const serverUrl = `http://localhost:${process.env.SERVER || "3001"}/api`;

    useEffect(() => {
        fetch(`${serverUrl}/todos`)
            .then(res => res.json())
            .then(data => setTodos(data));
    }, []);

    const addTodo = async () => {
        if (newTodoText.trim() === '') return;

        const response = await fetch(`${serverUrl}/todos`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            },
            body: JSON.stringify({text: newTodoText}),
        });

        if (response.ok) {
            const newTodo = await response.json();
            setTodos([...todos, newTodo]);
            setNewTodoText('');
        }
    };

    const toggleTodo = async (id: number) => {
        const response = await fetch(`${serverUrl}/todos/toggle/${id}`, {method: 'POST'});

        if (response.ok) {
            const updatedTodo = await response.json();
            setTodos(todos.map(todo => (todo.id === id ? updatedTodo : todo)));
        }
    };

    const deleteTodo = async (id: number) => {
        const response = await fetch(`${serverUrl}/todos/${id}`, {method: 'DELETE'});

        if (response.ok) {
            setTodos(todos.filter(todo => todo.id !== id));
        }
    };

    const clearCompleted = async () => {
        const response = await fetch(`${serverUrl}/todos/clear-completed`, {method: 'POST'});

        if (response.ok) {
            setTodos(await response.json());
        }
    };

    const toggleAll = async () => {
        const response = await fetch(`${serverUrl}/todos/toggle-all`, {method: 'POST'});

        if (response.ok) {
            setTodos(await response.json());
        }
    };

    const filteredTodos = todos.filter(todo =>
        filter === 'all' ? true : filter === 'active' ? !todo.checked : todo.checked
    );

    return (
        <div className="todoapp">
            <header className="header">
                <h1>todos</h1>
                <input
                    className="new-todo"
                    placeholder="What needs to be done?"
                    value={newTodoText}
                    onChange={e => setNewTodoText(e.target.value)}
                    onKeyDown={e => e.key === 'Enter' && addTodo()}
                />
            </header>
            <section className="main">
                <input
                    id="toggle-all"
                    className="toggle-all"
                    type="checkbox"
                    onChange={toggleAll}
                    checked={todos.every(todo => todo.checked)}
                />
                <label htmlFor="toggle-all">Mark all as complete</label>
                <ul className="todo-list">
                    {filteredTodos.map(todo => (
                        <li key={todo.id} className={todo.checked ? 'completed' : ''}>
                            <div className="view">
                                <input
                                    className="toggle"
                                    type="checkbox"
                                    checked={todo.checked}
                                    onChange={() => toggleTodo(todo.id)}
                                />
                                <label>{todo.text}</label>
                                <button className="destroy" onClick={() => deleteTodo(todo.id)}></button>
                            </div>
                        </li>
                    ))}
                </ul>
            </section>
            <footer className="footer">
        <span className="todo-count">
          <strong>{todos.filter(todo => !todo.checked).length}</strong> items left
        </span>
                <ul className="filters">
                    <li>
                        <a href="#/" className={filter === 'all' ? 'selected' : ''} onClick={() => setFilter('all')}>
                            All
                        </a>
                    </li>
                    <li>
                        <a href="#/active" className={filter === 'active' ? 'selected' : ''}
                           onClick={() => setFilter('active')}>
                            Active
                        </a>
                    </li>
                    <li>
                        <a href="#/completed" className={filter === 'completed' ? 'selected' : ''}
                           onClick={() => setFilter('completed')}>
                            Completed
                        </a>
                    </li>
                </ul>
                {todos.some(todo => todo.checked) && (
                    <button className="clear-completed" onClick={clearCompleted}>
                        Clear completed
                    </button>
                )}
            </footer>
        </div>
    );
};

export default App;