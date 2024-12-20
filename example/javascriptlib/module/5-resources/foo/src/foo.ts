import * as fs from 'fs/promises';

const Resources: string = process.env.RESOURCES || "@foo/resources" // `RESOURCES` is generated on bundle
const File = require.resolve(`${Resources}/file.txt`);

export default class Foo {
    static async resourceText(): Promise<string> {
        return await fs.readFile(File, 'utf8')
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