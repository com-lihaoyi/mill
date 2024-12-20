// @ts-nocheck
import {pathsToModuleNameMapper} from 'node_modules/ts-jest';
import {compilerOptions} from './tsconfig.json'; // this is a generated file.

// Remove unwanted keys
const moduleDeps = {...compilerOptions.paths};
delete moduleDeps['*'];
delete moduleDeps['typeRoots'];

// moduleNameMapper evaluates in order they appear,
// sortedModuleDeps makes sure more specific path mappings always appear first
const sortedModuleDeps = Object.keys(moduleDeps)
    .sort((a, b) => b.length - a.length) // Sort by descending length
    .reduce((acc, key) => {
        acc[key] = moduleDeps[key];
        return acc;
    }, {});

export default {
    preset: 'ts-jest',
    testEnvironment: 'node',
    testMatch: [
        '<rootDir>/**/**/*.test.ts',
        '<rootDir>/**/**/*.test.js',
    ],
    transform: {
        '^.+\\.(ts|tsx)$': ['ts-jest', { tsconfig: 'tsconfig.json' }],
        '^.+\\.(js|jsx)$': 'babel-jest', // Use babel-jest for JS/JSX files
    },
    moduleFileExtensions: ['ts', 'tsx', 'js', 'jsx', 'json', 'node'],
    moduleNameMapper: pathsToModuleNameMapper(sortedModuleDeps) // use absolute paths generated in tsconfig.
};