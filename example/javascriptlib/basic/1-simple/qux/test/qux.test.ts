import { generateUser } from "../src/qux";

// Define the type roles object
type RoleKeys = "admin" | "user";
type Roles = {
    [key in RoleKeys]: string;
};

// Mock the defaultRoles.get method
jest.mock("foo/bar/bar", () => ({
    defaultRoles: {
        get: jest.fn((role: string, defaultValue: string) => {
            const roles: Roles = { "admin": "Administrator", "user": "User" };
            return roles[role as RoleKeys] || defaultValue;
        }),
    },
}));

describe("generateUser function", () => {
    afterEach(() => {
        jest.clearAllMocks();
    });

    test("should generate a user with all specified fields", () => {
        const args = ["John", "Doe", "admin"];
        const user = generateUser(args);

        expect(user).toEqual({
            firstName: "John",
            lastName: "Doe",
            role: "Administrator",
        });
    });

    test("should default lastName and role when they are not provided", () => {
        const args = ["Jane"];
        const user = generateUser(args);

        expect(user).toEqual({
            firstName: "Jane",
            lastName: "unknown",
            role: "",
        });
    });

    test("should default all fields when args is empty", () => {
        const args: string[] = [];
        const user = generateUser(args);

        expect(user).toEqual({
            firstName: "unknown",
            lastName: "unknown",
            role: "",
        });
    });
});