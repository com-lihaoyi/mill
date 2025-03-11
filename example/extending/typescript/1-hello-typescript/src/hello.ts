interface User {
    firstName: string
    lastName: string
    role: string
}

const user: User = {
    firstName: process.argv[2],
    lastName: process.argv[3],
    role: "Professor",
}

console.log("Hello " + user.name + " " + user.lastName)