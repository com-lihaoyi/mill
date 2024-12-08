import express, {Express} from 'express';
import cors from 'cors';
import path from 'path'
import api from "./api"

const app: Express = express();
const port = process.env.PORT || 3001;

const client = process.env.CLIENT_PATH || ""

app.use(cors());
app.use(express.json());

// Middleware to serve static files from the "build" directory
app.use(express.static(path.join(client)));

// Mount API routes at /api
app.use('/api', api);

// Serve the React app for all other routes
app.get('*', (req, res) => {
    res.sendFile(path.join(client, 'index.html'));
});


if (process.env.NODE_ENV !== 'test') {
    app.listen(port, () => {
        console.log(`Server listening on port ${port}`);
    });
}

export default app;