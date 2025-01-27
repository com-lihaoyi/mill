import {defaultRoles} from 'bar/bar';
import {compare} from 'bar/test/utils/bar.tests.utils';

test('defaultRoles map should have correct values', () => {
    expect(compare(defaultRoles.size, 2)).toBeTruthy()
    expect(compare(defaultRoles.get('prof'), 'Professor')).toBeTruthy()
    expect(compare(defaultRoles.get('student'), 'Student')).toBeTruthy()
    expect(compare(defaultRoles.has('admin'), false)).toBeTruthy()
});