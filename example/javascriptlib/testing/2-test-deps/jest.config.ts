import {pathsToModuleNameMapper} from 'ts-jest';
import {compilerOptions} from './tsconfig.json'; // this is a generated file.

// Remove unwanted keys
const moduleDeps = {...compilerOptions.paths};
delete moduleDeps['*'];
delete moduleDeps['typeRoots'];


export default {
    preset: 'ts-jest',
    testEnvironment: 'node',
    testMatch: [
        '<rootDir>/**/**/**/*.test.ts',
        '<rootDir>/**/**/**/*.test.js',
    ],
    transform: {
        '^.+\\.(ts|tsx)$': ['ts-jest', {tsconfig: 'tsconfig.json'}],
        '^.+\\.(js|jsx)$': 'babel-jest', // Use babel-jest for JS/JSX files
    },
    moduleFileExtensions: ['ts', 'tsx', 'js', 'jsx', 'json', 'node'],
    moduleNameMapper: pathsToModuleNameMapper(moduleDeps) // use absolute paths generated in tsconfig.
};