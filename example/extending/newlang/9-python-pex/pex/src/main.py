import numpy as np
import pandas as pd

def generate_random_data(num_rows: int) -> pd.DataFrame:
    """Generates a DataFrame with random data."""
    data = {
        'A': np.random.rand(num_rows),  # Random floats
        'B': np.random.randint(1, 100, num_rows),  # Random integers
        'C': np.random.choice(['X', 'Y', 'Z'], num_rows)  # Random choices
    }
    return pd.DataFrame(data)

def main():
    num_rows = 5
    df = generate_random_data(num_rows)
    print("Generated DataFrame:")
    print(df)

if __name__ == "__main__":
    main()
