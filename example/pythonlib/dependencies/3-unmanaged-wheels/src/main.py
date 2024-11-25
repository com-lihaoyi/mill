# this comes from a wheel
import jinja2

# this comes from an sdist
import orjson as oj

environment = jinja2.Environment()
template = environment.from_string("Hello, {{ name }}!")
print(oj.dumps(template.render(name="world")))
