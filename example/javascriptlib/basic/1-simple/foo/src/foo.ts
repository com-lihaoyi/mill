import {Map} from 'immutable';

interface User {
    firstName: string
    lastName: string
    role: string
}

export const defaultRoles: Map<string, string> = Map({prof: "Professor"});

/**
 * Generate a user object based on command-line arguments
 * @param args Command-line arguments
 * @returns User object
 */
export function generateUser(args: string[]): User {
    return {
        firstName: args[0] || "unknown",
        lastName: args[1] || "unknown",
        role: defaultRoles.get(args[2], ""),
    };
}

// Main CLI logic
if (process.env.NODE_ENV !== "test") {
    const args = process.argv.slice(2); // Skip 'node' and script name
    const user = generateUser(args);

    console.log(defaultRoles.toObject());
    console.log(args[2]);
    console.log(defaultRoles.get(args[2]));
    console.log("Hello " + user.firstName + " " + user.lastName + " " + user.role);
}