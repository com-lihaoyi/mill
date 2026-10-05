from app.message import message
from raw_uv_project import raw_message


def test_message() -> None:
    assert message() == "Hello from the bare root!"
    assert raw_message() == "Hello from a raw uv project!"
