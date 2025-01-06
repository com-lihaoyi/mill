"use strict";
var __awaiter = (this && this.__awaiter) || function (thisArg, _arguments, P, generator) {
    function adopt(value) { return value instanceof P ? value : new P(function (resolve) { resolve(value); }); }
    return new (P || (P = Promise))(function (resolve, reject) {
        function fulfilled(value) { try { step(generator.next(value)); } catch (e) { reject(e); } }
        function rejected(value) { try { step(generator["throw"](value)); } catch (e) { reject(e); } }
        function step(result) { result.done ? resolve(result.value) : adopt(result.value).then(fulfilled, rejected); }
        step((generator = generator.apply(thisArg, _arguments || [])).next());
    });
};
var __generator = (this && this.__generator) || function (thisArg, body) {
    var _ = { label: 0, sent: function() { if (t[0] & 1) throw t[1]; return t[1]; }, trys: [], ops: [] }, f, y, t, g = Object.create((typeof Iterator === "function" ? Iterator : Object).prototype);
    return g.next = verb(0), g["throw"] = verb(1), g["return"] = verb(2), typeof Symbol === "function" && (g[Symbol.iterator] = function() { return this; }), g;
    function verb(n) { return function (v) { return step([n, v]); }; }
    function step(op) {
        if (f) throw new TypeError("Generator is already executing.");
        while (g && (g = 0, op[0] && (_ = 0)), _) try {
            if (f = 1, y && (t = op[0] & 2 ? y["return"] : op[0] ? y["throw"] || ((t = y["return"]) && t.call(y), 0) : y.next) && !(t = t.call(y, op[1])).done) return t;
            if (y = 0, t) op = [op[0] & 2, t.value];
            switch (op[0]) {
                case 0: case 1: t = op; break;
                case 4: _.label++; return { value: op[1], done: false };
                case 5: _.label++; y = op[1]; op = [0]; continue;
                case 7: op = _.ops.pop(); _.trys.pop(); continue;
                default:
                    if (!(t = _.trys, t = t.length > 0 && t[t.length - 1]) && (op[0] === 6 || op[0] === 2)) { _ = 0; continue; }
                    if (op[0] === 3 && (!t || (op[1] > t[0] && op[1] < t[3]))) { _.label = op[1]; break; }
                    if (op[0] === 6 && _.label < t[1]) { _.label = t[1]; t = op; break; }
                    if (t && _.label < t[2]) { _.label = t[2]; _.ops.push(op); break; }
                    if (t[2]) _.ops.pop();
                    _.trys.pop(); continue;
            }
            op = body.call(thisArg, _);
        } catch (e) { op = [6, e]; y = 0; } finally { f = t = 0; }
        if (op[0] & 5) throw op[1]; return { value: op[0] ? op[1] : void 0, done: true };
    }
};
Object.defineProperty(exports, "__esModule", { value: true });
var test_1 = require("@playwright/test");
var URL = "http://localhost:3000";
test_1.test.describe('Fibonacci endpoint', function () {
    (0, test_1.test)('returns correct fibonacci numbers', function (_a) { return __awaiter(void 0, [_a], void 0, function (_b) {
        var testCases, _i, testCases_1, _c, n, expected, response, body;
        var request = _b.request;
        return __generator(this, function (_d) {
            switch (_d.label) {
                case 0:
                    testCases = [
                        { n: 0, expected: 0 },
                        { n: 1, expected: 1 },
                        { n: 5, expected: 5 },
                        { n: 10, expected: 55 }
                    ];
                    _i = 0, testCases_1 = testCases;
                    _d.label = 1;
                case 1:
                    if (!(_i < testCases_1.length)) return [3 /*break*/, 5];
                    _c = testCases_1[_i], n = _c.n, expected = _c.expected;
                    return [4 /*yield*/, request.get("".concat(URL, "/fibonacci?n=").concat(n))];
                case 2:
                    response = _d.sent();
                    return [4 /*yield*/, response.json()];
                case 3:
                    body = _d.sent();
                    (0, test_1.expect)(response.ok()).toBeTruthy();
                    (0, test_1.expect)(body.result).toBe(expected);
                    _d.label = 4;
                case 4:
                    _i++;
                    return [3 /*break*/, 1];
                case 5: return [2 /*return*/];
            }
        });
    }); });
    (0, test_1.test)('handles invalid input', function (_a) { return __awaiter(void 0, [_a], void 0, function (_b) {
        var testCases, _i, testCases_2, _c, input, expectedError, response, body;
        var request = _b.request;
        return __generator(this, function (_d) {
            switch (_d.label) {
                case 0:
                    testCases = [
                        { input: 'abc', expectedError: 'Please provide a valid number' },
                        { input: '-1', expectedError: 'Please provide a non-negative number' },
                        { input: '101', expectedError: 'Please provide a number less than or equal to 100' }
                    ];
                    _i = 0, testCases_2 = testCases;
                    _d.label = 1;
                case 1:
                    if (!(_i < testCases_2.length)) return [3 /*break*/, 5];
                    _c = testCases_2[_i], input = _c.input, expectedError = _c.expectedError;
                    return [4 /*yield*/, request.get("".concat(URL, "/fibonacci?n=").concat(input))];
                case 2:
                    response = _d.sent();
                    return [4 /*yield*/, response.json()];
                case 3:
                    body = _d.sent();
                    (0, test_1.expect)(response.status()).toBe(400);
                    (0, test_1.expect)(body.error).toBe(expectedError);
                    _d.label = 4;
                case 4:
                    _i++;
                    return [3 /*break*/, 1];
                case 5: return [2 /*return*/];
            }
        });
    }); });
});
