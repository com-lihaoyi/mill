import pandas as pd

def pandas_example():
    """Example using Pandas: Create a DataFrame and perform basic operations."""
    data = {
        "Name": ["Alice", "Bob", "Charlie"],
        "Age": [26, 30, 22],
        "City": ["New York", "Los Angeles", "Chicago"]
    }
    df = pd.DataFrame(data)
    
    return(f"| Pandas Example: Average Age: {df['Age'].mean()} ; Cities: {df['City'].unique()}")

def main():
    return(pandas_example())

if __name__ == "__main__":
    main()
