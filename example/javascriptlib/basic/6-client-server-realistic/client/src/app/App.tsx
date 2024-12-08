import React, {useState, useEffect} from 'react';

interface Todo {
  id: number;
  text: string;
}

const App: React.FC = () => {
  const [todos, setTodos] = useState<Todo[]>([]);
  const [newTodoText, setNewTodoText] = useState('');
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
    } else {
      console.error('Error adding todo:', response.status);
    }
  };

  return (
      <div>
        <h1>My Todos</h1>
        <ul>
          {todos.map(todo => (
              <li key={todo.id}>{todo.text}</li>
          ))}
        </ul>
        <input
            type="text"
            value={newTodoText}
            onChange={e => setNewTodoText(e.target.value)}
        />
        <button onClick={addTodo}>Add Todo</button>
      </div>
  );
};

export default App;