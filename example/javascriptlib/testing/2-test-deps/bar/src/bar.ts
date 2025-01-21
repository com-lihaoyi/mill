import {Map} from 'immutable';

export default interface User {
    firstName: string
    lastName: string
    role: string
}

const defaultRoles: Map<string, string> = Map({
    prof: "Professor",
    student: "Student",
});

export {defaultRoles}