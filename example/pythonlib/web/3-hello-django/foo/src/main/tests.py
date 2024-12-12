from django.test import TestCase
from django.urls import reverse

class TestScript(TestCase):
    def test_index_view(self):
        """
        Test that the index view returns a 200 status code
        and the expected HTML content.
        """
        response = self.client.get(reverse('homepage'))
        self.assertEqual(response.status_code, 200)
        self.assertContains(response, '<h1>Hello, Mill!</h1>')
