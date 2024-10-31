import numpy as np
import pandas as pd
import requests

def numpy_example():
    """Example using NumPy: Create an array and perform basic operations."""
    array = np.array([1, 2, 3, 4, 5])
    return(f"NumPy Example: Array: {array} ; Mean: {np.mean(array)} ; Sum: {np.sum(array)}")


def pandas_example():
    """Example using Pandas: Create a DataFrame and perform basic operations."""
    data = {
        "Name": ["Alice", "Bob", "Charlie"],
        "Age": [26, 30, 22],
        "City": ["New York", "Los Angeles", "Chicago"]
    }
    df = pd.DataFrame(data)
    
    return(f"| Pandas Example: Average Age: {df["Age"].mean()} ; Cities: {df['City'].unique()}")

def requests_example():
    """Example using Requests: Fetch data from a public API."""
    response = requests.get("https://mill-build.org/mill/")
    
    return(f"| Requests Example: Requests is working! ; Status Code={response.status_code}" if response.ok else "Failed to fetch data")

def main():
    print(numpy_example(), pandas_example(), requests_example())

if __name__ == "__main__":
    main()
