#!/usr/bin/env node

import User from "foo/foo";
import DefaultRoles from "foo/bar/bar";
import {generateUser} from "./generate_user";

// Main CLI logic
if (require.main === module) {
    const args = process.argv.slice(2); // Skip 'node' and script name
    const user: User = generateUser(args);

    console.log(DefaultRoles.toObject());
    console.log(args[2]);
    console.log(DefaultRoles.get(args[2]));
    console.log("Hello " + user.firstName + " " + user.lastName + " " + user.role);
}