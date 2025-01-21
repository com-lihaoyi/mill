import * as fs from 'fs/promises';

const Resources: string = process.env.RESOURCESDEST || "@foo/resources.dest" // `RESOURCES` is generated on bundle
const LineCount = require.resolve(`${Resources}/line-count.txt`);

export default class Foo {
    static async getLineCount(): Promise<string> {
        return await fs.readFile(LineCount, 'utf-8');
    }
}

if (process.env.NODE_ENV !== "test") {
    (async () => {
        try {
            const lineCount = await Foo.getLineCount();
            console.log('Line Count:', lineCount);
        } catch (err) {
            console.error('Error:', err);
        }
    })()
}