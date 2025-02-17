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