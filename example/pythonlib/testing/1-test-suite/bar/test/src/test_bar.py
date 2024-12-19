import pytest
from unittest.mock import MagicMock
from bar import Bar  # type: ignore


def test_hello():
    result = Bar().main()
    assert result.startswith("Hello"), "Result does not start with 'Hello'"


def test_world():
    result = Bar().main()
    assert result.endswith("World"), "Result does not end with 'World'"


def test_mock():
    mock_bar = MagicMock(spec=Bar)
    mock_bar.main.return_value = "Hello Mockito World"
    result = mock_bar.main()

    assert (
        result == "Hello Mockito World"
    ), "Mocked main() did not return expected value"
    mock_bar.main.assert_called_once()
