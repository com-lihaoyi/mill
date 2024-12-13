import generateUser from "foo/foo";
import {compareObject} from 'bar/test/utils/bar.tests.utils'

// Define the type roles object
type RoleKeys = "admin" | "user";
type Roles = {
    [key in RoleKeys]: string;
};

// Mock the defaultRoles.get method
jest.mock("bar/bar", () => ({
    defaultRoles: {
        get: jest.fn((role: string, defaultValue: string) => {
            const roles: Roles = {"admin": "Administrator", "user": "User"};
            return roles[role as RoleKeys] || defaultValue;
        }),
    },
}));

describe("generateUser function", () => {
    beforeAll(() => {
        process.env.NODE_ENV = "test"; // Set NODE_ENV for all tests
    });

    afterEach(() => {
        jest.clearAllMocks();
    });

    test("should generate a user with all specified fields", () => {
        const args = ["John", "Doe", "admin"];
        const user = generateUser(args);

        expect(compareObject({...user}, {
            firstName: "John",
            lastName: "Doe",
            role: "Administrator",
        })).toBeTruthy()
    });

    test("should default lastName and role when they are not provided", () => {
        const args = ["Jane"];
        const user = generateUser(args);

        expect(compareObject(user, {
            firstName: "Jane",
            lastName: "unknown",
            role: "",
        })).toBeTruthy()
    });

    test("should default all fields when args is empty", () => {
        const args: string[] = [];
        const user = generateUser(args);

        expect(compareObject(user, {
            firstName: "unknown",
            lastName: "unknown",
            role: "",
        })).toBeTruthy()
    });
});