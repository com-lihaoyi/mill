import * as http from 'http';
import {getServerResponse} from '../src/client';

jest.mock('http');

describe('getServerResponse', () => {
    it('should return mocked response', async () => {
        const mockedResponse = 'Mocked response data';
        const mockRequest = jest.fn((options, callback) => {
            const mockRes = {
                on: jest.fn((event, listener) => {
                    if (event === 'data') {
                        listener(mockedResponse);
                    }
                    if (event === 'end') {
                        listener();
                    }
                }),
            };
            callback(mockRes);
            return {
                on: jest.fn(),
                end: jest.fn(),
            };
        });


        (http.request as jest.Mock).mockImplementation(mockRequest);

        const response = await getServerResponse('testPath');
        expect(response).toBe(mockedResponse);
    });
});
