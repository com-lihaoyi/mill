interface User {
    firstName: string
}

const user: User = {firstName: process.argv[2]}

console.log("Hello " + user.firstName + " Bar")

export {User}