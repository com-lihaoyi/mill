import express, {Express} from 'express';
import cors from 'cors';
import api from "./api"

const Resources: string = (process.env.RESOURCESDEST || "@server/resources") + "/build" // `RESOURCES` is generated on bundle
const Client = require.resolve(`${Resources}/index.html`);
const BuildPath = Client.replace(/index\.html$/, "");
const app: Express = express();
const port = process.env.PORT || 3000;

app.use(cors());
app.use(express.json());

// Middleware to serve static files from the "build" directory
app.use(express.static(BuildPath));

// Mount API routes at /api
app.use('/api', api);

// Serve the React app for all other routes
app.get('*', (req, res) => {
    res.sendFile(Client);
});


if (process.env.NODE_ENV !== 'test') {
    app.listen(port, () => {
        console.log(`Server listening on port ${port}`);
    });
}

export default app;