import React, {useState} from 'react';
import 'src/App.css';

const App = () => {
    const [inputText, setInputText] = useState('');
    const [result, setResult] = useState('');
    const [loading, setLoading] = useState(false);
    const [sentiment, setSentiment] = useState('neutral');  // New state for sentiment style

    const handleSubmit = async (e) => {
        e.preventDefault();
        setLoading(true);
        setResult('');
        setSentiment('neutral');

        try {
            const response = await fetch('http://localhost:8086/api/analysis', {
                method: 'POST',
                headers: {
                    'Content-Type': 'text/plain',
                },
                body: inputText,
            });

            if (response.ok) {
                const responseData = await response.text();
                setResult(responseData);

                // Determine sentiment from the response
                const polarityMatch = responseData.match(/polarity: ([+-]?[0-9]*\.?[0-9]+)/);
                if (polarityMatch) {
                    const polarity = parseFloat(polarityMatch[1]);
                    setSentiment(polarity > 0 ? 'positive' : 'negative');
                }
            } else {
                setResult('Error occurred during analysis.');
            }
        } catch (error) {
            setResult('Network error: Could not connect to the server.');
        } finally {
            setLoading(false);
        }
    };

    return (
        <div className="app-container">
            <h1>Text Analysis Tool</h1>
            <form onSubmit={handleSubmit} className="analysis-form">
        <textarea
            value={inputText}
            onChange={(e) => setInputText(e.target.value)}
            placeholder="Enter your text here..."
            required
        />
                <button type="submit" disabled={loading}>
                    {loading ? 'Analyzing...' : 'Analyze'}
                </button>
            </form>
            {result && (
                <div className={`result-container ${sentiment}`}>
                    <h2>Analysis Result:</h2>
                    <p>{result}</p>
                </div>
            )}
        </div>
    );
};

export default App;