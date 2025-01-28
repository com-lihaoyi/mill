// @ts-check

import eslint from '@eslint/js';
import tseslint from 'typescript-eslint';

export default tseslint.config({
    files: ['*/**/*.ts'],
    extends: [
        eslint.configs.recommended,
        ...tseslint.configs.recommended,
    ],
    rules: {
        // styling rules
        'semi': ['error', 'always'],
        'quotes': ['error', 'single'],
        'comma-dangle': ['error', 'always-multiline'],
        'max-len': ['error', {code: 80, ignoreUrls: true}],
        'indent': ['error', 2, {SwitchCase: 1}],
        'brace-style': ['error', '1tbs'],
        'space-before-function-paren': ['error', 'never'],
        'no-multi-spaces': 'error',
        'array-bracket-spacing': ['error', 'never'],
        'object-curly-spacing': ['error', 'always'],
        'arrow-spacing': ['error', {before: true, after: true}],
        'key-spacing': ['error', {beforeColon: false, afterColon: true}],
        'keyword-spacing': ['error', {before: true, after: true}],
        'space-infix-ops': 'error',
        'block-spacing': ['error', 'always'],
        'eol-last': ['error', 'always'],
        'newline-per-chained-call': ['error', {ignoreChainWithDepth: 2}],
        'padded-blocks': ['error', 'never'],
    },
});