import {Calculator} from '../../../src/calculator';

describe('Calculator', () => {
    const calculator = new Calculator();

    describe('Addition', () => {
        test('should return the sum of two numbers', () => {
            const result = calculator.add(2, 3);
            expect(result).toEqual(5);
        });

        test('should return the correct sum for negative numbers', () => {
            const result = calculator.add(-2, -3);
            expect(result).toEqual(-5);
        });
    });

    describe('Division', () => {
        test('should return the quotient of two numbers', () => {
            const result = calculator.divide(6, 3);
            expect(result).toEqual(2);
        });

        it('should throw an error when dividing by zero', () => {
            expect(() => calculator.divide(6, 0)).toThrow("Division by zero is not allowed");
        });
    });
});