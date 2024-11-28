import request from 'supertest';
import {Express} from "express";
import * as http from "node:http";

describe('Server Tests', () => {
    let app: Express;
    let server: http.Server

    beforeAll((done) => {
        process.env.PORT = "3002";
        process.env.NODE_ENV = 'test';
        app = require('../src/server').default;
        server = app.listen(process.env.PORT)
        done()
    });

    afterAll((done) => {
        server.close(done);
    });


    it('should return an empty array of todos', async () => {
        const response = await request(app).get('/todos');
        expect(response.status).toBe(200);
        expect(response.body).toEqual([]);
    });

    it('should add a new todo', async () => {
        const newTodoText = 'Buy milk';
        const response = await request(app)
            .post('/todos')
            .send({text: newTodoText});

        expect(response.status).toBe(201);
        expect(response.body.text).toBe(newTodoText);
        expect(response.body.id).toBeDefined();

        const getResponse = await request(app).get('/todos');
        expect(getResponse.body.length).toBe(1);
        expect(getResponse.body[0].text).toBe(newTodoText);
    });

    it('should handle errors gracefully', async () => {
        const response = await request(app).post('/todos').send({});
        expect(response.status).toBe(400);
        expect(response.body).toHaveProperty('error'); //Check for error message
    });

    it('should handle invalid todo text gracefully', async () => {
        const response = await request(app).post('/todos').send({text: '   '}); //Whitespace only
        expect(response.status).toBe(400);
        expect(response.body).toHaveProperty('error'); //Check for error message
    });
});