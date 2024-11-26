import * as http from 'http';
import * as readline from 'readline';

export function getServerResponse(path: string): Promise<string> {
    const port = process.env.SERVER_PORT || '3000'; // Default to 3000 if not provided

    return new Promise<string>((resolve, reject) => {
        const options = {
            hostname: 'localhost',
            port: parseInt(port, 10),
            path: `/${path}`,
            method: 'GET',
        };

        const req = http.request(options, (res) => {
            let data = '';
            res.on('data', (chunk) => (data += chunk));
            res.on('end', () => resolve(data));
        });

        req.on('error', (err) => reject(err));
        req.end();
    });
}

if (require.main === module) {
    (async () => {
        const rl = readline.createInterface({
            input: process.stdin,
            output: process.stdout,
        });

        console.log('Client is running. Enter a request path:');

        for await (const line of rl) {
            const input = line.trim();
            if (!input) {
                console.log('Please enter a valid input.');
                continue;
            }

            try {
                const response = await getServerResponse(input);
                console.log('Response from server:', response);
            } catch (error) {
                console.error('Error connecting to server:', error);
            }
            console.log('Enter a path:');
        }
    })();
}