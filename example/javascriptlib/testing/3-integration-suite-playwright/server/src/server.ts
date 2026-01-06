import express, {Express} from 'express';
import cors from 'cors';

const Resources: string = (process.env.RESOURCESDEST || "@server/resources") + "/build" // `RESOURCES` is generated on bundle
const Client = require.resolve(`${Resources}/index.html`);

const app: Express = express();
const port = process.env.PORT || 3000;
const BuildPath = Client.replace(/index\.html$/, "");

app.use(cors());
app.use(express.json());

// Middleware to serve static files from the "build" directory
app.use(express.static(BuildPath));

app.listen(port, () => {
    console.log(`Server listening on port ${port}`);
});

export default app;