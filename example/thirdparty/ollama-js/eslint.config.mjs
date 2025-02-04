// eslint.config.mjs

import eslint from '@eslint/js';
import tsEslint from '@typescript-eslint/eslint-plugin';
import tsParser from '@typescript-eslint/parser';

export default [
    {
        // Define environment
        files: ['*.ts', '*.tsx', '*.js', '*.jsx'],
        languageOptions: {
            ecmaVersion: 'latest',
            sourceType: 'module',
            parser: tsParser,
            parserOptions: {
                project: './tsconfig.json',
            },
        },
        env: {
            commonjs: true,
            es2021: true,
            node: true,
        },
        plugins: {
            '@typescript-eslint': tsEslint,
        },
        rules: {
            curly: ['warn', 'all'],
            'arrow-parens': 'off',
            'generator-star-spacing': 'off',
            'no-unused-vars': ['off', { args: 'after-used', vars: 'local' }],
            'no-constant-condition': 'off',
            'no-debugger': process.env.NODE_ENV === 'production' ? 'error' : 'off',
        },
    },
    {
        // Define extended configurations
        files: ['*.ts', '*.tsx'],
        extends: [
            eslint.configs.recommended,
            tsEslint.configs['eslint-recommended'],
            tsEslint.configs.recommended,
        ],
    },
];