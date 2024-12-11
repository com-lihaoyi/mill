import {defaultRoles} from '../src/bar';
import {Map} from 'node_modules/immutable';
import {compare} from './utils/bar.tests.utils';

test('defaultRoles map should have correct values', () => {
    expect(compare(defaultRoles.size, 2)).toBeTruthy()
    expect(compare(defaultRoles.get('prof'), 'Professor')).toBeTruthy()
    expect(compare(defaultRoles.get('student'), 'Student')).toBeTruthy()
    expect(compare(defaultRoles.has('admin'), false)).toBeTruthy()
});

test('defaultRoles map should be an instance of Immutable Map', () => {
    expect(compare((defaultRoles instanceof Map), true)).toBeTruthy()
});