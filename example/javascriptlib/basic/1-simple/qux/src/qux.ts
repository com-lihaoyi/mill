import {User} from "foo/foo";
import {defaultRoles} from "foo/bar/bar";

/**
 * Generate a user object based on command-line arguments
 * @param args Command-line arguments
 * @returns User object
 */
export function generateUser(args: string[]): User {
    return {
        firstName: args[0] || "unknown", // Default to "unknown" if first-name not found
        lastName: args[1] || "unknown", // Default to "unknown" if last-name not found
        role: defaultRoles.get(args[2], ""), // Default to empty string if role not found
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