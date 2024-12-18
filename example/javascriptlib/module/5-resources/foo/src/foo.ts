import * as fs from 'fs/promises';
require('tsconfig-paths/register');
const Resources = "@foo/resources"

export default class Foo {
    static async resourceText(): Promise<string> {
        try {
            const filePath = require.resolve(`${Resources}/file.txt`);
            return await fs.readFile(filePath, 'utf8');
        } catch (err) {
            console.error('Error reading the file:', err);
            throw err;
        }
    }
}

if (process.env.NODE_ENV !== "test") {
    (async () => {
        try {
            console.log(await Foo.resourceText());
        } catch (err) {
            console.error('Error:', err);
        }
    })();
}