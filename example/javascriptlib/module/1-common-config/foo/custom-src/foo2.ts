import * as fs from 'fs/promises';
import FooA from "@generated/foo-A";
import FooB from "@generated/foo-B";
import FooC from "@generated/foo-C";

const Resources: string = process.env.RESOURCES || "@foo/resources" // `RESOURCES` is generated on bundle
const CustomResources: string = process.env.CUSTOMRESOURCES || "@foo/custom-resources"

const MyResource = require.resolve(`${Resources}/MyResource.txt`);
const MyOtherResource = require.resolve(`${CustomResources}/MyOtherResource.txt`);


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
    console.log("MyResource: " + await Foo2.resourceText(MyResource))
    console.log("MyOtherResource: " + await Foo2.resourceText(MyOtherResource))
})()
