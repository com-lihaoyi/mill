import { FastifyRequest, FastifyReply } from 'fastify';
import Fastify from 'fastify';
const port = process.env.PORT || 3000;

const fastify = Fastify({
  logger: true
});

interface FibonacciRequest {
  Querystring: {
    n?: string;
  }
}

function computeFibonacci(n: number): number {
  if (n <= 1) return n;

  let prev = 0, curr = 1;
  for (let i = 2; i <= n; i++) {
    const next = prev + curr;
    prev = curr;
    curr = next;
  }
  return curr;
}

// Index page
fastify.get('/', async (request: FastifyRequest, reply: FastifyReply) => {
  return reply
    .header('Content-Type', 'text/html')
    .send(`
      <html>
        <head>
          <title>Javascript Integration Testing</title>
        </head>
        <body>
          <h1>Welcome to Javascript Integration Testing using Playwright</h1>
          <button id="counter" onclick="this.textContent = Number(this.textContent) + 1">0</button>
        </body>
      </html>
    `);
});

// Fibonacci page
fastify.get<FibonacciRequest>('/fibonacci', async (request, reply) => {
  const { n } = request.query;
  const number = parseInt(n || '0');

  if (isNaN(number)) {
    return reply
      .status(400)
      .send({ n: number, error: 'Please provide a valid number' });
  }

  if (number < 0) {
    return reply
      .status(400)
      .send({ n: number, error: 'Please provide a non-negative number' });
  }

  if (number > 100) {
    return reply
      .status(400)
      .send({ n: number, error: 'Please provide a number less than or equal to 100' });
  }

  const result = computeFibonacci(number);
  return { n: number, result };
});


// Run the server
(async () => {
  try {
    await fastify.listen({ port: +port });
    console.log(`Server running at http://localhost:${port}`);
  } catch (err) {
    fastify.log.error(err);
    process.exit(1);
  }
})();
