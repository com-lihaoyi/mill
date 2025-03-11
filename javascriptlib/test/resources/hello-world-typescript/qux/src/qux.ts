import {User} from "foo/bar/bar"

const user: User = {firstName: process.argv[2]}

console.log("Hello " + user.firstName + " Qux")