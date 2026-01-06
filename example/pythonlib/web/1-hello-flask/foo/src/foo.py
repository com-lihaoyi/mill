import os
from flask import Flask

app = Flask(__name__)


@app.route("/")
def hello_world():
    return "<h1>Hello, Mill!</h1>"


if __name__ == "__main__":
    port = int(os.environ.get("PORT", 5000))
    app.run(debug=True, port=port)
