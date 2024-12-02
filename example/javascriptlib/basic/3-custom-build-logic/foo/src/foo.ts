import * as fs from 'fs';
import * as path from 'path';

export default class Foo {
    static getLineCount(resourcePath: string): string | null {
        try {
            const filePath = path.join(resourcePath, 'line-count.txt');
            console.log('[Reading file:]', filePath);
            return fs.readFileSync(filePath, 'utf-8');
        } catch (error) {
            console.error('Error reading file:', error);
            return null;
        }
    }
}

if (process.env.NODE_ENV !== "test") {
    let resourcePath = process.argv[2];
    if (!resourcePath) resourcePath = process.env.RESOURCE_PATH;
    // no resource found, exit
    if (!resourcePath) {
        console.error('Error: No resource path provided.');
        process.exit(1);
    }

    const lineCount = Foo.getLineCount(resourcePath);
    console.log('Line Count:', lineCount);
}