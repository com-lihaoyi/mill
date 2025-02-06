import unittest
from foo import analyze_sentiment

class TestSentimentAnalysis(unittest.TestCase):

    def test_positive_sentiment(self):
        text = "This is amazing!"
        result = analyze_sentiment(text)
        self.assertTrue(result.startswith("Positive sentiment"))

    def test_negative_sentiment(self):
        text = "This is terrible!"
        result = analyze_sentiment(text)
        self.assertTrue(result.startswith("Negative sentiment"))

    def test_neutral_sentiment(self):
        text = "You suck"
        result = analyze_sentiment(text)
        self.assertEqual(result, "Neutral sentiment (polarity: 0)")


if __name__ == "__main__":
    unittest.main()
