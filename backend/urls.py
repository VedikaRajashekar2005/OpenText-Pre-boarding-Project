from django.contrib import admin
from django.urls import path

from . import models as game

urlpatterns = [
    path('admin/', admin.site.urls),

    path('api/auth/csrf/', game.csrf_bootstrap, name='csrf-bootstrap'),
    path('api/auth/register/', game.register, name='register'),
    path('api/auth/login/', game.login_view, name='login'),
    path('api/auth/logout/', game.logout_view, name='logout'),
    path('api/auth/me/', game.me, name='me'),

    path('api/game/start/', game.start_game, name='game-start'),
    path('api/game/<int:session_id>/guess/', game.submit_guess, name='game-guess'),
    path('api/game/<int:session_id>/hint/', game.hint, name='game-hint'),
    path('api/game/<int:session_id>/', game.game_detail, name='game-detail'),
    path('api/game/history/', game.game_history, name='game-history'),

    path('api/reports/daily/', game.daily_report, name='report-daily'),
    path('api/reports/user/<int:user_id>/', game.user_report, name='report-user'),
    path('api/users/', game.user_list, name='user-list'),
]
