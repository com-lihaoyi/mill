# import argparse
# from argparse import Namespace
# from jinja2 import Template
#
#
# def generate_html(text: str) -> str:
#     template = Template("<h1>{{ text }}</h1>")
#     return template.render(text=text)
#
#
# def main() -> str:
#     parser = argparse.ArgumentParser(description="Inserts text into an HTML template")
#     parser.add_argument("-t", "--text", required=True, help="Text to insert")
#
#     args: Namespace = parser.parse_args()
#     html: str = generate_html(args.text)
#     return html
#
#
# if __name__ == "__main__":
#     print(main())

import sys
from textblob import TextBlob

def analyze_sentiment(text):
    blob = TextBlob(text)
    polarity = blob.sentiment.polarity

    if polarity > 0:
        return f"Positive sentiment (polarity: {polarity})"
    elif polarity < 0:
        return f"Negative sentiment (polarity: {polarity})"
    else:
        return "Neutral sentiment (polarity: 0)"

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python sentiment.py <text>")
        sys.exit(1)

    input_text = " ".join(sys.argv[1:])
    result = analyze_sentiment(input_text)
    print(result)