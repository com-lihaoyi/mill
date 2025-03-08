import {generateUser, defaultRoles} from "foo/foo";
import {Map} from 'immutable';

// Define the type roles object
type RoleKeys = "admin" | "user";
type Roles = {
    [key in RoleKeys]: string;
};

// Mock `defaultRoles` as a global variable for testing
const mockDefaultRoles = Map<string, string>({
    admin: "Administrator",
    user: "User",
});

describe("generateUser function", () => {
    beforeAll(() => {
        process.env.NODE_ENV = "test"; // Set NODE_ENV for all tests
    });

    afterEach(() => {
        jest.clearAllMocks();
    });

    test("should generate a user with all specified fields", () => {
        // Override the `defaultRoles` map for testing
        (defaultRoles as any).get = mockDefaultRoles.get.bind(mockDefaultRoles);

        const args = ["John", "Doe", "admin"];
        const user = generateUser(args);

        expect(user).toEqual({
            firstName: "John",
            lastName: "Doe",
            role: "Administrator",
        });
    });

    test("should default lastName and role when they are not provided", () => {
        (defaultRoles as any).get = mockDefaultRoles.get.bind(mockDefaultRoles);

        const args = ["Jane"];
        const user = generateUser(args);

        expect(user).toEqual({
            firstName: "Jane",
            lastName: "unknown",
            role: "",
        });
    });

    test("should default all fields when args is empty", () => {
        (defaultRoles as any).get = mockDefaultRoles.get.bind(mockDefaultRoles);

        const args: string[] = [];
        const user = generateUser(args);

        expect(user).toEqual({
            firstName: "unknown",
            lastName: "unknown",
            role: "",
        });
    });
});