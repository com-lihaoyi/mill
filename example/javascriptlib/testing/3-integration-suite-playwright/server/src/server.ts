import * as http from 'http';
import * as fs from 'fs';
import * as path from 'path';

const Resources: string = (process.env.RESOURCESDEST || "@server/resources.dest") + "/build" // `RESOURCES` is generated on bundle
const Client = require.resolve(`${Resources}/index.html`);

const server = http.createServer((req, res) => {
    if (req.url?.startsWith('/api') && req.method === 'GET') {
        if (req.url === '/api/hello') {
            res.writeHead(200, {'Content-Type': 'text/plain'});
            res.end('Hello from the server!');
        }
    } else {
        // Serve static files or fallback to index.html for React routes
        const buildPath = Client.replace(/index\.html$/, "");
        const requestedPath = path.join(buildPath, req.url || '');
        const filePath = path.extname(requestedPath)
            ? requestedPath
            : Client;

        fs.readFile(filePath, (err, data) => {
            if (err) {
                if (err.code === 'ENOENT') {
                    // If file not found, fallback to index.html for React routes
                    fs.readFile(Client, (error, indexData) => {
                        if (error) {
                            res.writeHead(500, {'Content-Type': 'text/plain'});
                            res.end('Internal Server Error');
                        } else {
                            res.writeHead(200, {'Content-Type': 'text/html'});
                            res.end(indexData);
                        }
                    });
                }
            } else {
                // Serve the static file
                const ext = path.extname(filePath);
                const contentType = getContentType(ext);
                res.writeHead(200, {'Content-Type': contentType});
                res.end(data);
            }
        });
    }
});

const PORT = process.env.PORT || 3000;
server.listen(PORT, () => {
    console.log(`Server running on http://localhost:${PORT}`);
});

export default server;

// Helper function to determine content type based on file extension
function getContentType(ext: string): string {
    switch (ext) {
        case '.html':
            return 'text/html';
        case '.js':
            return 'application/javascript';
        case '.css':
            return 'text/css';
        default:
            return 'application/octet-stream';
    }
}