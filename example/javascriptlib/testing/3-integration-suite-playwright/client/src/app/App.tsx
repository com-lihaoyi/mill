import React from 'react';
import logo from 'src/logo.svg';
import 'src/App.css';

function App() {
  return (
    <div className="App">
      <header className="App-header">
        <img src={logo} className="App-logo" alt="logo"/>
        <h1 data-testid="heading">Hello, Cypress & PlayWright</h1>
        <p>Brought to you by ✨✨mill.✨✨</p>
        <a
            className="App-link"
            href="https://reactjs.org"
            target="_blank"
            rel="noopener noreferrer"
        >
          Learn React
        </a>
      </header>
    </div>
  );
}

export default App;
