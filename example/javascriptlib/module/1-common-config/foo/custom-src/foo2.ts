import * as fs from 'fs/promises';
import FooA from "@generated/foo-A";
import FooB from "@generated/foo-B";
import FooC from "@generated/foo-C";
import Resources from "foo/resources/index";
import CustomResources from "foo/custom-resources/index";

export default class Foo2 {
    static value: string = "hello2"

    static async resourceText(filePath): Promise<string> {
        try {
            return await fs.readFile(filePath, 'utf8');
        } catch (err) {
            console.error('Error reading the file:', err);
            throw err;
        }
    }
}

(async function () {
    console.log(Foo2.value)
    console.log(FooA.value)
    console.log(FooB.value)
    console.log(FooC.value)
    console.log(process.env.MY_CUSTOM_ENV)
    console.log("MyResource: " + await Foo2.resourceText(Resources.MyResource))
    console.log("MyOtherResource: " + await Foo2.resourceText(CustomResources.MyOtherResource))
})()
