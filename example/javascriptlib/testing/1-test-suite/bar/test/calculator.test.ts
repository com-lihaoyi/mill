import {Calculator} from '../src/calculator';

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

    describe('Subtraction', () => {
        test('should return the difference of two numbers', () => {
            const result = calculator.subtract(5, 3);
            expect(result).toEqual(2);
        });

        test('should return the correct difference for negative numbers', () => {
            const result = calculator.subtract(-5, -3);
            expect(result).toEqual(-2);
        });
    });

    describe('Multiplication', () => {
        test('should return the product of two numbers', () => {
            const result = calculator.multiply(2, 3);
            expect(result).toEqual(6);
        });

        test('should return the correct product for negative numbers', () => {
            const result = calculator.multiply(-2, -3);
            expect(result).toEqual(6);
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

    describe('Chained Calculations', () => {
        test('should correctly handle chained calculations', () => {
            const result = calculator.divide(
                calculator.multiply(
                    calculator.add(10, 5), // 15
                    calculator.subtract(20, 10) // 10
                ),
                1
            );
            expect(result).toEqual(150);
        });
    });
});