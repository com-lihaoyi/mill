import os

class Foo:
    def main(self) -> None:
        print(os.environ.get("MY_ENV_VAR"))
        
if __name__ == '__main__':
    Foo().main()