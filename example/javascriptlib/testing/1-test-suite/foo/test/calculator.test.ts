import {expect} from 'chai';
import {Calculator} from '../src/calculator';

describe('Calculator', () => {
    const calculator = new Calculator();

    describe('Addition', () => {
        it('should return the sum of two numbers', () => {
            const result = calculator.add(2, 3);
            expect(result).to.equal(5);
        });

        it('should return the correct sum for negative numbers', () => {
            const result = calculator.add(-2, -3);
            expect(result).to.equal(-5);
        });
    });

    describe('Subtraction', () => {
        it('should return the difference of two numbers', () => {
            const result = calculator.subtract(5, 3);
            expect(result).to.equal(2);
        });

        it('should return the correct difference for negative numbers', () => {
            const result = calculator.subtract(-5, -3);
            expect(result).to.equal(-2);
        });
    });

    describe('Multiplication', () => {
        it('should return the product of two numbers', () => {
            const result = calculator.multiply(2, 3);
            expect(result).to.equal(6);
        });

        it('should return the correct product for negative numbers', () => {
            const result = calculator.multiply(-2, -3);
            expect(result).to.equal(6);
        });
    });

    describe('Division', () => {
        it('should return the quotient of two numbers', () => {
            const result = calculator.divide(6, 3);
            expect(result).to.equal(2);
        });

        it('should throw an error when dividing by zero', () => {
            expect(() => calculator.divide(6, 0)).to.throw("Division by zero is not allowed");
        });
    });

    describe('Chained Calculations', () => {
        it('should correctly handle chained calculations', () => {
            const result = calculator.divide(
                calculator.multiply(
                    calculator.add(10, 5), // 15
                    calculator.subtract(20, 10) // 10
                ),
                1
            );
            expect(result).to.equal(150);
        });
    });
});