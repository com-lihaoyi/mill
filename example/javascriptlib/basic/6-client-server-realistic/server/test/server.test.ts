import request from 'supertest';
import {Express} from 'express';
import * as http from 'node:http';

describe('Server Tests', () => {
    let app: Express;
    let server: http.Server;

    beforeAll((done) => {
        process.env.PORT = '3002';
        process.env.NODE_ENV = 'test';
        app = require('../src/server').default;
        server = app.listen(process.env.PORT, done);
    });

    afterAll((done) => {
        server.close(done);
    });

    test('returns an empty array initially', async () => {
        const response = await request(app).get('/api/todos');
        expect(response.status).toBe(200);
        expect(response.body).toEqual([]);
    });

    test('adds a new todo', async () => {
        const response = await request(app)
            .post('/api/todos')
            .send({text: 'Learn Node.js'});

        expect(response.status).toBe(201);
        expect(response.body.text).toBe('Learn Node.js');
        expect(response.body.id).toBeDefined();

        const getResponse = await request(app).get('/api/todos');
        expect(getResponse.body).toHaveLength(1);
        expect(getResponse.body[0].text).toBe('Learn Node.js');
    });

    test('toggles a todo', async () => {
        // Add a todo item
        const todo = {id: 1, text: 'Learn React', checked: false};
        await request(app).post('/api/todos').send(todo);

        // Toggle the todo
        await request(app).post(`/api/todos/toggle/${todo.id}`)

        // Fetch todos to verify the update
        const getResponse = await request(app).get('/api/todos');

        // Assert the first todo is toggled
        expect(getResponse.body[0].checked).toBe(true);
    });
});