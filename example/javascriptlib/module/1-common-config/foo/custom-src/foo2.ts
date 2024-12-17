import * as fs from 'fs/promises';
import * as path from 'path';
import FooA from "@generated/foo-A";
import FooB from "@generated/foo-B";
import FooC from "@generated/foo-C";
import Resources from "foo/resources/index";

export default class Foo2 {
    static value: string = "hello2"
}

(async function () {
    console.log(Foo2.value)
    console.log(FooA.value)
    console.log(FooB.value)
    console.log(FooC.value)
    console.log(process.env.MY_CUSTOM_ENV)

    const filePath = path.join(Resources.MyResource);
    let resourceText: string;
    try {
        resourceText = await fs.readFile(filePath, 'utf8');
    } catch (err) {
        console.error('Error reading the file:', err);
        throw err;
    }

    console.log("MyResource: " + resourceText)
})()
