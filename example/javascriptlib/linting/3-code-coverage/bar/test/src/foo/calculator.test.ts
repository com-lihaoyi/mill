import {expect} from 'chai';
import {Calculator} from 'bar/calculator';

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

});