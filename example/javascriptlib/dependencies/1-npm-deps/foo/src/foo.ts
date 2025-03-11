import {sortBy} from 'lodash'

const args = process.argv.slice(2);
console.log(`Sorted with lodash: [${sortBy(args).join(",")}]`);
