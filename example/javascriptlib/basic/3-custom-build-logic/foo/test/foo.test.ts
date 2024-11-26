import * as fs from 'fs';
import * as path from 'path';
import Foo from '../src';

// Mock the 'fs' module
jest.mock('fs');

describe('Foo.getLineCount', () => {
    const mockResourcePath = path.join(__dirname, '../resources');
    const mockFilePath = path.join(mockResourcePath, 'line-count.txt');

    let consoleLogSpy: jest.SpyInstance;
    let consoleErrorSpy: jest.SpyInstance;

    beforeEach(() => {
        process.env.NODE_ENV = "test"; // Set NODE_ENV for all tests
        jest.clearAllMocks();
        // Mock console.log and console.error
        consoleLogSpy = jest.spyOn(console, 'log').mockImplementation(() => {});
        consoleErrorSpy = jest.spyOn(console, 'error').mockImplementation(() => {});
    });

    afterEach(() => {
        consoleLogSpy.mockRestore();
        consoleErrorSpy.mockRestore();
        jest.clearAllMocks();
    });

    it('should return the content of the line-count.txt file', () => {
        const mockContent = '42';
        jest.spyOn(fs, 'readFileSync').mockReturnValue(mockContent);

        const result = Foo.getLineCount(mockResourcePath);
        expect(result).toBe(mockContent);
        expect(fs.readFileSync).toHaveBeenCalledWith(mockFilePath, 'utf-8');
    });

    it('should return null if the file cannot be read', () => {
        jest.spyOn(fs, 'readFileSync').mockImplementation(() => {
            throw new Error('File not found');
        });

        const result = Foo.getLineCount(mockResourcePath);
        expect(result).toBeNull();
        expect(fs.readFileSync).toHaveBeenCalledWith(mockFilePath, 'utf-8');
    });
});