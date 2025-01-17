#!/usr/bin/env node

export class Foo {
    static hello() {
        const args = process.argv.slice(2);
        const name = args[0] || 'unknown';
        return `Hello ${name}!`
    }
}

console.log(Foo.hello());