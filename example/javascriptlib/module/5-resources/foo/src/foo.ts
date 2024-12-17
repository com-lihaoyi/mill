import * as fs from 'fs/promises';
import * as path from 'path';
import Resources from "foo/resources/index";

export default class Foo {
    static async resourceText(): Promise<string> {
        const filePath = path.join(Resources.file1);
        try {
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