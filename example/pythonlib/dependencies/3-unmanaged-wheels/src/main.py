# this comes from a wheel
import jinja2

environment = jinja2.Environment()
template = environment.from_string("Hello, {{ name }}!")
print(template.render(name="world"))
