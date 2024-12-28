import {sortBy} from 'node_modules/lodash'

const args = process.argv.slice(2);
console.log(`Sorted with lodash: [${sortBy(args).join(",")}]`);