import {User} from "foo/foo.js"
import {defaultRoles} from "foo/bar/bar.js"
const user: User = {
    firstName: process.argv[2],
    lastName: process.argv[3],
    role: defaultRoles.get(process.argv[4]),
}


console.log(defaultRoles.toObject())
console.log(process.argv[4])
console.log(defaultRoles.get(process.argv[4]))
console.log("Hello " + user.firstName + " " + user.lastName + " " + user.role)