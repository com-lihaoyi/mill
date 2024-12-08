const config = {
    transform: {
        '^.+\\.[t|j]sx?$': 'babel-jest',
    },
    preset: "ts-jest/presets/js-with-babel",
    transformIgnorePatterns: ["/node_modules/(?!(@bundled-es-modules)/)"],
    testEnvironment: 'jsdom',
    moduleFileExtensions: ['ts', 'tsx', 'js', 'jsx'],
    moduleNameMapper: {
        '^.+\\.css$': 'identity-obj-proxy',
    },
    globals: {
        'ts-jest': {
            useESModules: true,
        },
    },
};

export default config;