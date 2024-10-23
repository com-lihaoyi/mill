import {User} from "foo/foo.js"
import {defaultRole} from "foo/bar/bar.js"
const user: User = {
    firstName: process.argv[2],
    lastName: process.argv[3],
    role: defaultRole,
}

console.log("Hello " + user.firstName + " " + user.lastName + " " + user.role)