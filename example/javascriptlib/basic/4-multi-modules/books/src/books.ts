import Author from 'authors/authors';

export default interface Books {
    id: number;
    title: string;
    publication: string;
    sn: string;
    author: Author;
}