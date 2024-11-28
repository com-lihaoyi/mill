import express, {Request, Response} from 'express';
import cors from 'cors';

const app = express();
const port = process.env.PORT || 3001;

app.use(cors());
app.use(express.json());

interface Todo {
    id: number;
    text: string;
}

let todos: Todo[] = [];
let nextId = 1;

app.get('/todos', (req: Request, res: Response) => {
    res.json(todos);
});

app.post('/todos', (req: Request, res: Response) => {
    const {text} = req.body;

    if (!text || text.trim() === '') {
        res.status(400).json({error: 'Todo text is required'});
    }

    const newTodo: Todo = {id: nextId++, text};
    todos.push(newTodo);
    res.status(201).json(newTodo);
});

if (process.env.NODE_ENV !== 'test') {
    app.listen(port, () => {
        console.log(`Server listening on port ${port}`);
    });
}

export default app; //Make the app exportable.