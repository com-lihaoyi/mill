import * as http from 'http';
import * as fs from 'fs';
import * as path from 'path';

const resource = process.env.SERVER_RESOURCES || ""
const client = resource + "/build"

const server = http.createServer((req, res) => {
    if (req.url?.startsWith('/api') && req.method === 'GET') {
        // Handle API routes
        if (req.url === '/api/hello') {
            res.writeHead(200, { 'Content-Type': 'text/plain' });
            res.end('Hello from the server!');
        } else {
            res.writeHead(404, { 'Content-Type': 'text/plain' });
            res.end('API Not Found');
        }
    } else {
        // Serve static files or fallback to index.html for React routes
        const requestedPath = path.join(client, req.url || '');
        const filePath = path.extname(requestedPath)
            ? requestedPath // Serve the requested file if it has an extension
            : path.join(client, 'index.html'); // Fallback to index.html for non-file paths

        fs.readFile(filePath, (err, data) => {
            if (err) {
                if (err.code === 'ENOENT') {
                    // If file not found, fallback to index.html for React routes
                    fs.readFile(path.join(client, 'index.html'), (error, indexData) => {
                        if (error) {
                            res.writeHead(500, { 'Content-Type': 'text/plain' });
                            res.end('Internal Server Error');
                        } else {
                            res.writeHead(200, { 'Content-Type': 'text/html' });
                            res.end(indexData);
                        }
                    });
                } else {
                    res.writeHead(500, { 'Content-Type': 'text/plain' });
                    res.end('Internal Server Error');
                }
            } else {
                // Serve the static file
                const ext = path.extname(filePath);
                const contentType = getContentType(ext);
                res.writeHead(200, { 'Content-Type': contentType });
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
        case '.png':
            return 'image/png';
        case '.jpg':
            return 'image/jpeg';
        case '.svg':
            return 'image/svg+xml';
        case '.json':
            return 'application/json';
        default:
            return 'application/octet-stream';
    }
}