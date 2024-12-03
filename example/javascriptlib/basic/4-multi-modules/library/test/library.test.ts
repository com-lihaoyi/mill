import Index from '../src/library';
import * as fs from 'fs';
import * as path from 'path';

jest.mock('fs');

describe('Library', () => {
    const mockResourcePath = '/mock/resource/path';

    const mockAuthors = [
        {id: 1, name: 'Author One'},
        {id: 2, name: 'Author Two'}
    ];

    const mockBooks = [
        {id: 1, title: 'Book One', publication: 2001, sn: 'SN001', author: {id: 1}},
        {id: 2, title: 'Book Two', publication: 2002, sn: 'SN002', author: {id: 1}},
        {id: 3, title: 'Book Three', publication: 2003, sn: 'SN003', author: {id: 2}}
    ];

    beforeEach(() => {
        process.env.NODE_ENV = "test"; // Set NODE_ENV for all tests
        jest.resetAllMocks();

        // Mock the readJsonFile function
        (fs.readFileSync as jest.Mock).mockImplementation((filePath: string) => {
            if (filePath.endsWith('authors.json')) {
                return JSON.stringify(mockAuthors);
            } else if (filePath.endsWith('books.json')) {
                return JSON.stringify(mockBooks);
            }
            throw new Error(`Unexpected file path: ${filePath}`);
        });
    });

    it('should return all authors with their books when no authorName is provided', () => {
        const result = Index.getLibraryData(mockResourcePath);

        expect(result).toEqual([
            {
                author: 'Author One',
                books: [
                    {title: 'Book One', publication: 2001, sn: 'SN001'},
                    {title: 'Book Two', publication: 2002, sn: 'SN002'}
                ]
            },
            {
                author: 'Author Two',
                books: [
                    {title: 'Book Three', publication: 2003, sn: 'SN003'}
                ]
            }
        ]);
    });

    it('should return books for a specific author when authorName is provided', () => {
        const result = Index.getLibraryData(mockResourcePath, 'Author One');

        expect(result).toEqual([
            {
                author: 'Author One',
                books: [
                    {title: 'Book One', publication: 2001, sn: 'SN001'},
                    {title: 'Book Two', publication: 2002, sn: 'SN002'}
                ]
            }
        ]);
    });

    it('should throw an error if the author is not found', () => {
        expect(() => Index.getLibraryData(mockResourcePath, 'Unknown Author')).toThrow(
            "Author 'Unknown Author' not found"
        );
    });
});