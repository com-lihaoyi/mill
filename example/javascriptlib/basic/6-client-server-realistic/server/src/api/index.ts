import express, {Router, Request, Response} from 'express';

const router: Router = express.Router();

interface Todo {
    id: number;
    text: string;
    checked: boolean;
}

let todos: Todo[] = [];
let nextId = 1;

router.get('/todos', (req: Request, res: Response) => {
    res.json(todos);
});

router.post('/todos', (req: Request, res: Response) => {
    const {text} = req.body;

    if (!text || text.trim() === '') {
        res.status(400).json({error: 'Todo text is required'});
        return;
    }

    const newTodo: Todo = {id: nextId++, text, checked: false};
    todos.push(newTodo);
    res.status(201).json(newTodo);
});

router.post('/todos/toggle/:id', (req: Request, res: Response) => {
    const {id} = req.params;
    const todo = todos.find(todo => todo.id === Number(id));

    if (!todo) {
        res.status(404).json({error: 'Todo not found'});
        return
    }

    todo.checked = !todo.checked;
    res.json(todo);
});

router.delete('/todos/:id', (req: Request, res: Response) => {
    const {id} = req.params;
    todos = todos.filter(todo => todo.id !== Number(id));
    res.status(204).send();
});

router.post('/todos/clear-completed', (req: Request, res: Response) => {
    todos = todos.filter(todo => !todo.checked);
    res.json(todos);
});

router.post('/todos/toggle-all', (req: Request, res: Response) => {
    const allChecked = todos.every(todo => todo.checked);
    todos = todos.map(todo => ({...todo, checked: !allChecked}));
    res.json(todos);
});

export default router;