export default {
    preset: 'ts-jest',
    testEnvironment: 'node',
    testMatch: [
        '<rootDir>/**/test/**/*.test.ts',
        '<rootDir>/**/test/**/*.test.js',
    ],
    transform: {
        '^.+\\.(ts|tsx)$': ['ts-jest', { tsconfig: 'tsconfig.json' }],
        '^.+\\.(js|jsx)$': 'babel-jest', // Use babel-jest for JS/JSX files
    },
    moduleFileExtensions: ['ts', 'tsx', 'js', 'jsx', 'json', 'node']
};