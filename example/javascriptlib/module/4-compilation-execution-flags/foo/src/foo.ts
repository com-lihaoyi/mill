export default class Foo {
    constructor() {
        console.log('Foo:');
    }

    foo(): string {
        return "Hello World!"
    }
}

if (process.env.NODE_ENV !== "test") {
    const foo = new Foo();
    console.log(foo.foo());
}