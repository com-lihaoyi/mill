from django.urls import path
from . import views

urlpatterns = [
    path('', views.index, name='index'),
    path('list/<str:state>/', views.list_todos, name='list_todos'),
    path('add/<str:state>/', views.add, name='add'),
    path('delete/<str:state>/<int:index>/', views.delete, name='delete'),
    path('toggle/<str:state>/<int:index>/', views.toggle, name='toggle'),
    path('clear-completed/<str:state>/', views.clear_completed, name='clear_completed'),
    path('toggle-all/<str:state>/', views.toggle_all, name='toggle_all'),
    path('edit/<str:state>/<int:index>/', views.edit, name='edit'),
]