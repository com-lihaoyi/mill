import Author from 'authors/index';

export default interface Index {
    id: number;
    title: string;
    publication: string;
    sn: string;
    author: Author;
}