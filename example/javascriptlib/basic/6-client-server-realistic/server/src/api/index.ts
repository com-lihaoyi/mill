import express, {Router, Request, Response} from 'express';

const router: Router = express.Router();

interface Todo {
    id: number;
    text: string;
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
    }

    const newTodo: Todo = {id: nextId++, text};
    todos.push(newTodo);
    res.status(201).json(newTodo);
});

export default router