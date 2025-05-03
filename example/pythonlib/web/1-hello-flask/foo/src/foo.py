from flask import Flask
import os

app = Flask(__name__)


@app.route("/")
def hello_world():
    return "<h1>Hello, Mill!</h1>"


if __name__ == "__main__":
    port = os.environ['PORT']
    app.run(debug=True, port = port)
