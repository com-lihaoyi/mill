export class Calculator {
    add(a, b) {
        return a + b;
    }
    divide(a, b) {
        if (b === 0) {
            throw new Error("Division by zero is not allowed");
        }
        return a / b;
    }
}
