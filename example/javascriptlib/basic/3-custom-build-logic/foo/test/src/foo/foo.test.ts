import Foo from '../../../src/foo';

describe('Foo.getLineCount', () => {
    beforeEach(() => {
        process.env.NODE_ENV = "test"; // Set NODE_ENV for all tests
    });

    it('should return the content of the line-count.txt file', async () => {
        const expected: string = await Foo.getLineCount();
        expect(expected).toBe("21");
    });

});