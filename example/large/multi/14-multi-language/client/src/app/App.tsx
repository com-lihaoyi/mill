import React, {useState} from 'react';
import 'src/App.css';

const App = () => {
    const [inputText, setInputText] = useState('');
    const [result, setResult] = useState('');
    const [loading, setLoading] = useState(false);

    const handleSubmit = async (e) => {
        e.preventDefault();
        setLoading(true);
        setResult('');

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
                <div className="result-container">
                    <h2>Analysis Result:</h2>
                    <p>{result}</p>
                </div>
            )}
        </div>
    );
};

export default App;

// import React from 'react';
// import logo from 'src/logo.svg';
// import 'src/App.css';
//
// function App() {
//   return (
//     <div className="App">
//       <header className="App-header">
//         <img src={logo} className="App-logo" alt="logo" />
//         <p>
//           Edit <code>src/App.tsx</code> and save to reload.
//         </p>
//         <a
//           className="App-link"
//           href="https://reactjs.org"
//           target="_blank"
//           rel="noopener noreferrer"
//         >
//           Learn React
//         </a>
//       </header>
//     </div>
//   );
// }
//
// export default App;
