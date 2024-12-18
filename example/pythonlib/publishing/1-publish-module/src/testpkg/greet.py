import jinja2

def hello(name: str):
    environment = jinja2.Environment()
    template = environment.from_string("Hello, {{ name }}!")
    return template.render(name=name)
