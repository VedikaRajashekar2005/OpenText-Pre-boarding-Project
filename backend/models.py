import json
import random
import re
from datetime import date as date_cls
from functools import wraps

from django.conf import settings
from django.contrib import admin
from django.contrib.auth import authenticate
from django.contrib.auth import login as django_login
from django.contrib.auth import logout as django_logout
from django.contrib.auth.models import User
from django.db import models
from django.http import JsonResponse
from django.middleware.csrf import get_token
from django.utils import timezone
from django.views.decorators.csrf import ensure_csrf_cookie
from django.views.decorators.http import require_http_methods

class Word(models.Model):
    # Word categories
    CAT_ANIMAL    = 'animal'
    CAT_FLOWER    = 'flower'
    CAT_FOOD      = 'food'
    CAT_PLACE     = 'place'
    CAT_OBJECT    = 'object'
    CAT_ADJECTIVE = 'adjective'
    CAT_NATURE    = 'nature'
    CAT_OTHER     = 'other'
    CATEGORY_CHOICES = [
        (CAT_ANIMAL,    'Animal'),
        (CAT_FLOWER,    'Flower'),
        (CAT_FOOD,      'Food'),
        (CAT_PLACE,     'Place'),
        (CAT_OBJECT,    'Object'),
        (CAT_ADJECTIVE, 'Adjective'),
        (CAT_NATURE,    'Nature'),
        (CAT_OTHER,     'Other'),
    ]

    text      = models.CharField(max_length=5, unique=True)
    category  = models.CharField(
        max_length=12,
        choices=CATEGORY_CHOICES,
        default=CAT_OTHER,
    )
    is_active = models.BooleanField(default=True)

    def save(self, *args, **kwargs):
        self.text = self.text.upper()
        super().save(*args, **kwargs)

    def __str__(self):
        return f'{self.text} ({self.category})'


class GameSession(models.Model):
    STATUS_IN_PROGRESS = 'in_progress'
    STATUS_WON = 'won'
    STATUS_LOST = 'lost'
    STATUS_CHOICES = [
        (STATUS_IN_PROGRESS, 'In Progress'),
        (STATUS_WON, 'Won'),
        (STATUS_LOST, 'Lost'),
    ]
    MAX_GUESSES = 5

    user = models.ForeignKey(settings.AUTH_USER_MODEL, on_delete=models.CASCADE, related_name='game_sessions')
    word = models.ForeignKey(Word, on_delete=models.CASCADE, related_name='game_sessions')
    status = models.CharField(max_length=12, choices=STATUS_CHOICES, default=STATUS_IN_PROGRESS)
    played_on = models.DateField(default=timezone.localdate)
    created_at = models.DateTimeField(auto_now_add=True)
    finished_at = models.DateTimeField(null=True, blank=True)
    hints_given = models.JSONField(default=list, blank=True)

    @property
    def attempts_used(self):
        return self.guesses.count()

    @property
    def is_over(self):
        return self.status != self.STATUS_IN_PROGRESS

    def __str__(self):
        return f'{self.user.username} - {self.word.text} - {self.status}'


class UserHintQuota(models.Model):
    user = models.OneToOneField(settings.AUTH_USER_MODEL, on_delete=models.CASCADE, related_name='hint_quota')
    date = models.DateField(default=timezone.localdate)
    count = models.IntegerField(default=0)

    def __str__(self):
        return f'{self.user.username} - {self.date} - {self.count}'


class Guess(models.Model):
    session = models.ForeignKey(GameSession, on_delete=models.CASCADE, related_name='guesses')
    guess_number = models.PositiveSmallIntegerField()
    text = models.CharField(max_length=5)
    feedback = models.JSONField()  # list of 'green' | 'orange' | 'grey', one per letter
    created_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        ordering = ['guess_number']
        unique_together = ('session', 'guess_number')

    def __str__(self):
        return f'{self.session_id} #{self.guess_number}: {self.text}'


class GuessInline(admin.TabularInline):
    model = Guess
    extra = 0
    readonly_fields = ('guess_number', 'text', 'feedback', 'created_at')


@admin.register(Word)
class WordAdmin(admin.ModelAdmin):
    list_display  = ('text', 'category', 'is_active')
    list_filter   = ('category', 'is_active')
    search_fields = ('text',)


@admin.register(GameSession)
class GameSessionAdmin(admin.ModelAdmin):
    list_display = ('user', 'word', 'status', 'played_on', 'attempts_used')
    list_filter = ('status', 'played_on')
    inlines = [GuessInline]

# At least 5 letters total, must contain both an upper-case and a lower-case letter.
USERNAME_RE = re.compile(r'^(?=.*[a-z])(?=.*[A-Z])[A-Za-z]{5,}$')

# At least 5 characters, must contain a letter, a digit, and one of $ % *
PASSWORD_RE = re.compile(r'^(?=.*[A-Za-z])(?=.*\d)(?=.*[$%*]).{5,}$')

# Each entry: (word_text, category)
STARTER_WORDS = [
    # Animals
    ('TIGER', Word.CAT_ANIMAL),
    ('EAGLE', Word.CAT_ANIMAL),
    ('SHARK', Word.CAT_ANIMAL),
    ('BISON', Word.CAT_ANIMAL),
    ('KOALA', Word.CAT_ANIMAL),
    # Flowers
    ('TULIP', Word.CAT_FLOWER),
    ('LILAC', Word.CAT_FLOWER),
    ('DAISY', Word.CAT_FLOWER),
    ('PANSY', Word.CAT_FLOWER),
    ('LOTUS', Word.CAT_FLOWER),
    # Food
    ('BREAD', Word.CAT_FOOD),
    ('GRAPE', Word.CAT_FOOD),
    ('OLIVE', Word.CAT_FOOD),
    ('MANGO', Word.CAT_FOOD),
    ('PIZZA', Word.CAT_FOOD),
    # Nature
    ('RIVER', Word.CAT_NATURE),
    ('CLOUD', Word.CAT_NATURE),
    ('OCEAN', Word.CAT_NATURE),
    ('STORM', Word.CAT_NATURE),
    ('FLAME', Word.CAT_NATURE),
    # Adjectives
    ('BRAVE', Word.CAT_ADJECTIVE),
    ('LIGHT', Word.CAT_ADJECTIVE),
    ('SWEET', Word.CAT_ADJECTIVE),
    ('SHARP', Word.CAT_ADJECTIVE),
    ('CRISP', Word.CAT_ADJECTIVE),
    # Places
    ('PLAZA', Word.CAT_PLACE),
    ('TOWER', Word.CAT_PLACE),
    ('ARENA', Word.CAT_PLACE),
    ('DEPOT', Word.CAT_PLACE),
    ('HAVEN', Word.CAT_PLACE),
    # Objects
    ('CHAIR', Word.CAT_OBJECT),
    ('CLOCK', Word.CAT_OBJECT),
    ('BRUSH', Word.CAT_OBJECT),
    ('PIANO', Word.CAT_OBJECT),
    ('TORCH', Word.CAT_OBJECT),
    # Other
    ('DREAM', Word.CAT_OTHER),
    ('SMILE', Word.CAT_OTHER),
    ('HEART', Word.CAT_OTHER),
    ('MUSIC', Word.CAT_OTHER),
    ('WORLD', Word.CAT_OTHER),
]


def validate_username(value):
    if not USERNAME_RE.match(value):
        return (
            'Username must be at least 5 letters long and contain both '
            'upper case and lower case letters.'
        )
    if User.objects.filter(username__iexact=value).exists():
        return 'That username is already taken.'
    return None


def validate_password_rule(value):
    if not PASSWORD_RE.match(value):
        return (
            'Password must be at least 5 characters long and contain a letter, '
            'a number, and one of the special characters $, %, *.'
        )
    return None


def parse_json_body(request):
    try:
        return json.loads(request.body or b'{}')
    except json.JSONDecodeError:
        return {}


def score_guess(guess_text, answer_text):
    result = [None] * 5
    remaining = list(answer_text)

    for i in range(5):
        if guess_text[i] == answer_text[i]:
            result[i] = 'green'
            remaining[i] = None

    for i in range(5):
        if result[i] is not None:
            continue
        letter = guess_text[i]
        if letter in remaining:
            result[i] = 'orange'
            remaining[remaining.index(letter)] = None
        else:
            result[i] = 'grey'

    return result


def ensure_words_seeded():
    if not Word.objects.exists():
        for word_text, category in STARTER_WORDS:
            Word.objects.get_or_create(
                text=word_text,
                defaults={'category': category},
            )

def login_required_json(view_func):
    @wraps(view_func)
    def wrapper(request, *args, **kwargs):
        if not request.user.is_authenticated:
            return JsonResponse({'detail': 'Authentication required.'}, status=401)
        return view_func(request, *args, **kwargs)
    return wrapper


def admin_required_json(view_func):
    @wraps(view_func)
    def wrapper(request, *args, **kwargs):
        if not request.user.is_authenticated:
            return JsonResponse({'detail': 'Authentication required.'}, status=401)
        if not request.user.is_staff:
            return JsonResponse({'detail': 'Admin privileges are required for this action.'}, status=403)
        return view_func(request, *args, **kwargs)
    return wrapper


def session_to_dict(session):
    word_info = {'category': session.word.category}
    if session.status == GameSession.STATUS_LOST:
        word_info['text'] = session.word.text
        
    try:
        quota = UserHintQuota.objects.get(user=session.user)
        if quota.date != timezone.localdate():
            hints_remaining = 3
        else:
            hints_remaining = max(0, 3 - quota.count)
    except UserHintQuota.DoesNotExist:
        hints_remaining = 3

    return {
        'id': session.id,
        'status': session.status,
        'played_on': session.played_on.isoformat(),
        'attempts_used': session.attempts_used,
        'max_guesses': GameSession.MAX_GUESSES,
        'created_at': session.created_at.isoformat(),
        'hints_remaining': hints_remaining,
        'finished_at': session.finished_at.isoformat() if session.finished_at else None,
        'word': word_info,
        'guesses': [
            {'guess_number': g.guess_number, 'text': g.text, 'feedback': g.feedback}
            for g in session.guesses.all()
        ],
    }

@ensure_csrf_cookie
@require_http_methods(['GET'])
def csrf_bootstrap(request):
    """The React app calls this once on load to receive the csrftoken cookie."""
    get_token(request)
    return JsonResponse({'detail': 'CSRF cookie set.'})


@require_http_methods(['POST'])
def register(request):
    """Public endpoint: self-service registration always creates a Player (is_staff=False)."""
    data = parse_json_body(request)
    username = (data.get('username') or '').strip()
    password = data.get('password') or ''

    username_error = validate_username(username)
    if username_error:
        return JsonResponse({'username': [username_error]}, status=400)

    password_error = validate_password_rule(password)
    if password_error:
        return JsonResponse({'password': [password_error]}, status=400)

    is_admin = bool(data.get('is_admin'))
    user = User.objects.create_user(username=username, password=password)
    if is_admin:
        user.is_staff = True
        user.is_superuser = True
        user.save()
        
    return JsonResponse({'id': user.id, 'username': user.username, 'is_admin': user.is_staff}, status=201)


@require_http_methods(['POST'])
def login_view(request):
    data = parse_json_body(request)
    user = authenticate(request, username=data.get('username'), password=data.get('password'))
    if user is None:
        return JsonResponse({'detail': 'Invalid username or password.'}, status=400)

    django_login(request, user)
    return JsonResponse({'username': user.username, 'is_admin': user.is_staff})


@login_required_json
@require_http_methods(['POST'])
def logout_view(request):
    django_logout(request)
    return JsonResponse({'detail': 'Logged out.'})


@login_required_json
@require_http_methods(['GET'])
def me(request):
    """Lets the frontend restore auth state on page reload (the session cookie persists)."""
    return JsonResponse({'username': request.user.username, 'is_admin': request.user.is_staff})


@login_required_json
@require_http_methods(['POST'])
def start_game(request):
    """
    Starts a new game for the logged-in player: picks a random active word
    and creates a GameSession, unless the user already has one in progress
    or has hit the daily limit of MAX_GAMES_PER_DAY words.
    """
    ensure_words_seeded()
    user = request.user
    today = timezone.localdate()

    active = GameSession.objects.filter(user=user, status=GameSession.STATUS_IN_PROGRESS).first()
    if active:
        return JsonResponse(session_to_dict(active), status=200)
        
    games_today = GameSession.objects.filter(user=user, played_on=today).count()
    if games_today >= 3:
        return JsonResponse({'detail': 'You have already played 3 games today. Come back tomorrow!'}, status=403)

    word = Word.objects.filter(is_active=True).order_by('?').first()
    if not word:
        return JsonResponse({'detail': 'No words are configured yet. Please contact an admin.'}, status=500)

    session = GameSession.objects.create(user=user, word=word, played_on=today)
    return JsonResponse(session_to_dict(session), status=201)


@login_required_json
@require_http_methods(['POST'])
def submit_guess(request, session_id):
    user = request.user
    data = parse_json_body(request)
    guess_text = (data.get('guess') or '').strip().upper()

    if len(guess_text) != 5 or not guess_text.isalpha():
        return JsonResponse({'detail': 'Your guess must be exactly 5 letters.'}, status=400)

    try:
        session = GameSession.objects.select_related('word').get(id=session_id, user=user)
    except GameSession.DoesNotExist:
        return JsonResponse({'detail': 'Game not found.'}, status=404)

    if session.is_over:
        return JsonResponse({'detail': 'This game has already ended.'}, status=400)

    attempts_used = session.attempts_used
    if attempts_used >= GameSession.MAX_GUESSES:
        return JsonResponse({'detail': 'No guesses remaining.'}, status=400)

    answer = session.word.text
    feedback = score_guess(guess_text, answer)
    guess_number = attempts_used + 1

    Guess.objects.create(session=session, guess_number=guess_number, text=guess_text, feedback=feedback)

    if guess_text == answer:
        session.status = GameSession.STATUS_WON
        session.finished_at = timezone.now()
        session.save(update_fields=['status', 'finished_at'])
    elif guess_number >= GameSession.MAX_GUESSES:
        session.status = GameSession.STATUS_LOST
        session.finished_at = timezone.now()
        session.save(update_fields=['status', 'finished_at'])

    return JsonResponse(session_to_dict(session))


@login_required_json
@require_http_methods(['GET'])
def game_detail(request, session_id):
    try:
        session = GameSession.objects.select_related('word').get(id=session_id, user=request.user)
    except GameSession.DoesNotExist:
        return JsonResponse({'detail': 'Game not found.'}, status=404)
    return JsonResponse(session_to_dict(session))


@login_required_json
@require_http_methods(['GET'])
def game_history(request):
    sessions = GameSession.objects.select_related('word').filter(user=request.user).order_by('-created_at')
    return JsonResponse({'results': [session_to_dict(s) for s in sessions]})


@login_required_json
@require_http_methods(['POST'])
def hint(request, session_id):
    """Reveal one letter's position. Max 3 hints per calendar day, tracked per user."""
    today = timezone.localdate()
    quota, _ = UserHintQuota.objects.get_or_create(user=request.user)
    
    if quota.date != today:
        quota.date = today
        quota.count = 0

    if quota.count >= 3:
        return JsonResponse({'detail': 'You have used all 3 hints for today. Come back tomorrow!'}, status=400)

    try:
        session = GameSession.objects.select_related('word').get(id=session_id, user=request.user)
    except GameSession.DoesNotExist:
        return JsonResponse({'detail': 'Game not found.'}, status=404)

    if session.is_over:
        return JsonResponse({'detail': 'This game has already ended.'}, status=400)

    answer = session.word.text
    revealed = {i for g in session.guesses.all() for i, fb in enumerate(g.feedback) if fb == 'green'}
    revealed.update(session.hints_given)
    unrevealed = [i for i in range(5) if i not in revealed]

    if not unrevealed:
        return JsonResponse({'detail': 'All letters are already revealed!'}, status=400)

    pos = random.choice(unrevealed)
    session.hints_given.append(pos)
    session.save(update_fields=['hints_given'])
    
    quota.count += 1
    quota.save()

    return JsonResponse({'position': pos, 'letter': answer[pos], 'hints_remaining': 3 - quota.count})


@admin_required_json
@require_http_methods(['GET'])
def daily_report(request):
    """For a given day, how many users played and how many correct guesses (wins)."""
    date_str = request.GET.get('date')
    target_date = date_cls.fromisoformat(date_str) if date_str else timezone.localdate()

    sessions = GameSession.objects.filter(played_on=target_date)
    num_users = sessions.values('user').distinct().count()
    num_correct_guesses = sessions.filter(status=GameSession.STATUS_WON).count()

    return JsonResponse({
        'date': target_date.isoformat(),
        'num_users': num_users,
        'num_correct_guesses': num_correct_guesses,
    })


@admin_required_json
@require_http_methods(['GET'])
def user_report(request, user_id):
    """For a given user, a per-date breakdown of words tried and correct guesses."""
    try:
        target_user = User.objects.get(id=user_id)
    except User.DoesNotExist:
        return JsonResponse({'detail': 'User not found.'}, status=404)

    sessions = GameSession.objects.filter(user=target_user).order_by('played_on')
    by_date = {}
    for s in sessions:
        d = s.played_on.isoformat()
        entry = by_date.setdefault(d, {'date': d, 'words_tried': 0, 'correct_guesses': 0})
        entry['words_tried'] += 1
        if s.status == GameSession.STATUS_WON:
            entry['correct_guesses'] += 1

    report = sorted(by_date.values(), key=lambda row: row['date'], reverse=True)
    return JsonResponse({'user_id': target_user.id, 'username': target_user.username, 'report': report})


@admin_required_json
@require_http_methods(['GET'])
def user_list(request):
    """Lets admins pick which player to view a report for."""
    users = User.objects.filter(is_staff=False).values('id', 'username').order_by('username')
    return JsonResponse({'results': list(users)})
