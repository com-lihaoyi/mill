import * as http from 'http';

describe('Server Tests', () => {
    let server: http.Server;

    beforeAll(() => {
        server = require('../src/server').default;
    });

    afterAll(() => {
        server.close();
    });

    test('GET /hello should return "Hello from the server!"', async () => {
        const options = {
            hostname: 'localhost',
            port: 3000,
            path: '/hello',
            method: 'GET'
        };

        const response = await new Promise<string>((resolve, reject) => {
            const req = http.request(options, (res) => {
                let data = '';
                res.on('data', (chunk) => (data += chunk));
                res.on('end', () => resolve(data));
            });
            req.on('error', (err) => reject(err));
            req.end();
        });

        expect(response).toBe('Hello from the server!');
    });
});