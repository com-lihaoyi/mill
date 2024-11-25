import * as fs from 'fs';
import * as path from 'path';
import Book from 'books/index';
import Author from 'authors/index';

interface LibraryData {
    author: string;
    books: Pick<Book, 'title' | 'publication' | 'sn'>[];
}

function readJsonFile(filePath: string): any {
    return JSON.parse(fs.readFileSync(filePath, 'utf-8'));
}

export default class Library {
    static getLibraryData(resourcePath: string, authorName?: string): LibraryData[] {
        const authors: Author[] = readJsonFile(path.join(resourcePath, 'authors.json'));
        const books: Book[] = readJsonFile(path.join(resourcePath, 'books.json'));

        const authorMap = new Map<number, Author>();
        authors.forEach(author => authorMap.set(author.id, author));

        const result: LibraryData[] = [];

        if (authorName) {
            const author = authors.find(a => a.name === authorName);
            if (!author) {
                throw new Error(`Author '${authorName}' not found`);
            }
            const authorBooks = books
                .filter(book => book.author.id === author.id)
                .map(({id, author, ...rest}) => rest);
            result.push({author: author.name, books: authorBooks});
        } else {
            authorMap.forEach((author) => {
                const authorBooks = books
                    .filter(book => book.author.id === author.id)
                    .map(({id, author, ...rest}) => rest);
                if (authorBooks.length > 0) {
                    result.push({author: author.name, books: authorBooks});
                }
            });
        }

        return result;
    }
}

if (require.main === module) {
    const [resourcePath, authorName] = process.argv.slice(2);

    if (!resourcePath) {
        console.error('Error: No resource path provided.');
        process.exit(1);
    }

    try {
        const libraryData = Library.getLibraryData(resourcePath, authorName);
        console.log(JSON.stringify(libraryData, null, 2));
    } catch (err) {
        if (err instanceof Error) {
            console.error(err.message);
        } else {
            console.error('An unknown error occurred:', err);
        }
        process.exit(1);
    }
}