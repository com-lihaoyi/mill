import sys

class User:
    def __init__(self, first_name: str):
        self.first_name = first_name

user = User(first_name=sys.argv[1])

print("Hello " + user.first_name + " Qux")
