from foo.bar.src.bar import pandas_example
from foo.src.foo import numpy_example
import requests

def requests_example():
    """Example using Requests: Fetch data from a public API."""
    response = requests.get("https://mill-build.org/mill/")
    
    return(f"| Requests Example: Requests is working! ; Status Code={response.status_code}" if response.ok else "Failed to fetch data")

def main():
    print(numpy_example(), pandas_example(), requests_example())

if __name__ == "__main__":
    main()
