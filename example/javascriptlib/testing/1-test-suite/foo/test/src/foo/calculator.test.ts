import {expect} from 'chai';
import {Calculator} from 'foo/calculator';

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

    describe('Division', () => {
        it('should return the quotient of two numbers', () => {
            const result = calculator.divide(6, 3);
            expect(result).to.equal(2);
        });

        it('should throw an error when dividing by zero', () => {
            expect(() => calculator.divide(6, 0)).to.throw("Division by zero is not allowed");
        });
    });

});