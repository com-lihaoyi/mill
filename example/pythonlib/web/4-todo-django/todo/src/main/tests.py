from django.test import TestCase, Client
from django.urls import reverse
from .models import Todo

class TodoAppTests(TestCase):
    def setUp(self):
        self.client = Client()
        self.todo1 = Todo.objects.create(text="Get started with Django", checked=False)
        self.todo2 = Todo.objects.create(text="Profit!", checked=True)

    def test_index_view(self):
        response = self.client.get(reverse('index'))
        self.assertEqual(response.status_code, 200)
        self.assertTemplateUsed(response, 'index.html')
        self.assertContains(response, self.todo1.text)
        self.assertContains(response, self.todo2.text)

    def test_list_todos_view(self):
        response = self.client.post(reverse('list_todos', args=['all']))
        self.assertEqual(response.status_code, 200)
        self.assertContains(response, self.todo1.text)
        self.assertContains(response, self.todo2.text)

        response = self.client.post(reverse('list_todos', args=['active']))
        self.assertEqual(response.status_code, 200)
        self.assertContains(response, self.todo1.text)
        self.assertNotContains(response, self.todo2.text)

        response = self.client.post(reverse('list_todos', args=['completed']))
        self.assertEqual(response.status_code, 200)
        self.assertNotContains(response, self.todo1.text)
        self.assertContains(response, self.todo2.text)

    def test_add_todo_view(self):
        response = self.client.post(reverse('add', args=['all']), data="New Todo", content_type='text/plain')
        self.assertEqual(response.status_code, 200)
        self.assertContains(response, "New Todo")
        self.assertEqual(Todo.objects.count(), 3)
        todos = Todo.objects.all()
        self.assertEqual(todos.last().text, "New Todo")

    def test_delete_todo_view(self):
        response = self.client.post(reverse('delete', args=['all', self.todo1.id-1]))
        self.assertEqual(response.status_code, 200)
        self.assertNotContains(response, self.todo1.text)
        self.assertEqual(Todo.objects.count(), 1)

    def test_toggle_todo_view(self):
        response = self.client.post(reverse('toggle', args=['all', self.todo1.id-1]))

        self.assertEqual(response.status_code, 200)
        self.todo1.refresh_from_db()
        self.assertTrue(self.todo1.checked)

    def test_clear_completed_view(self):
        response = self.client.post(reverse('clear_completed', args=['all']))
        self.assertEqual(response.status_code, 200)
        self.assertEqual(Todo.objects.filter(checked=True).count(), 0)

    def test_toggle_all_view(self):
        response = self.client.post(reverse('toggle_all', args=['all']))
        self.assertEqual(response.status_code, 200)
        self.assertEqual(Todo.objects.filter(checked=True).count(), 2)

        response = self.client.post(reverse('toggle_all', args=['all']))
        self.assertEqual(response.status_code, 200)
        self.assertEqual(Todo.objects.filter(checked=True).count(), 0)

    def test_edit_todo_view(self):
        response = self.client.post(reverse('edit', args=['all', self.todo1.id-1]), data="Updated Todo", content_type='text/plain')
        self.assertEqual(response.status_code, 200)
        self.todo1.refresh_from_db()
        self.assertEqual(self.todo1.text, "Updated Todo")