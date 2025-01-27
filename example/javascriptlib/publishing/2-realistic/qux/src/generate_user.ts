import User from "foo/foo";
import DefaultRoles from "foo/bar/bar";

/**
 * Generate a user object based on command-line arguments
 * @param args Command-line arguments
 * @returns User object
 */
export function generateUser(args: string[]): User {
    return {
        firstName: args[0] || "unknown", // Default to "unknown" if first-name not found
        lastName: args[1] || "unknown", // Default to "unknown" if last-name not found
        role: DefaultRoles.get(args[2], ""), // Default to empty string if role not found
    };
}