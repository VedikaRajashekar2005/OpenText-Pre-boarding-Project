# Guess the Word

The project has two main application views:

1. **Player View** — registration, login, playing the word game, hints, game results, and logout.
2. **Admin View** — daily game statistics, player selection, per-player reports, and logout.

The application uses:

- **Frontend:** React 18 + Vite + React Router + Axios
- **Backend:** Django
- **Database:** PostgreSQL
- **Authentication:** Django session authentication + CSRF protection
- **API:** Django JSON endpoints

---

## 1. Project Structure

```text
Guess_the_Word/
│
├── backend/
│   ├── manage.py
│   ├── settings.py
│   ├── urls.py
│   ├── models.py
│   ├── wsgi.py
│   ├── .env
│   └── migrations/
│       └── 0001_initial.py
│
├── frontend/
│   ├── package.json
│   ├── package-lock.json
│   ├── vite.config.js
│   ├── index.html
│   ├── .env.example
│   └── src/
│       ├── App.jsx
│       ├── main.jsx
│       └── index.css
│
├── requirements.txt
└── README.md
```

---

## 2. PostgreSQL Database Setup

The Django backend is configured to use PostgreSQL.

The project expects these database settings:

```text
DB_NAME=guess_the_word_db
DB_USER=postgres
DB_PASSWORD=<your-postgres-password>
DB_HOST=localhost
DB_PORT=5432
```

### 2.1 Start PostgreSQL

Make sure the PostgreSQL service is running.

On Windows, you can check **Services** and look for the PostgreSQL service.

Alternatively, if PostgreSQL was installed with the standard tools, connect using:

```powershell
psql -U postgres
```

### 2.2 Create the database

Inside `psql`:

```sql
CREATE DATABASE guess_the_word_db;
```

Then exit:

```sql
\q
```

If the database already exists, do not create it again.

---

## 3. Configure Backend Environment Variables

The backend reads configuration from:

```text
backend/.env
```

A suitable local-development configuration is:

```env
DJANGO_SECRET_KEY=replace-with-a-local-secret
DJANGO_DEBUG=True
DJANGO_ALLOWED_HOSTS=localhost,127.0.0.1

DB_NAME=guess_the_word_db
DB_USER=postgres
DB_PASSWORD=your_postgres_password
DB_HOST=localhost
DB_PORT=5432

FRONTEND_ORIGIN=http://localhost:5173
```

---

## 4. Create a Python Virtual Environment

From the project root:

```powershell
python -m venv .venv
```

Activate it on Windows PowerShell:

```powershell
.\.venv\Scripts\Activate.ps1
```

If PowerShell blocks script execution, either use Command Prompt:

```cmd
.venv\Scripts\activate.bat
```

or activate the environment using your preferred Python environment manager.

After activation, the terminal should show something similar to:

```text
(.venv) PS E:\Projects\Guess_the_Word>
```

---

## 5. Install Backend Dependencies

From the project root:

```powershell
pip install -r requirements.txt
```

The required packages are:

- Django
- psycopg2-binary
- django-cors-headers
- python-dotenv

Verify Django:

```powershell
python -m django --version
```

---

## 6. Run Django Database Migrations

From the project root:

```powershell
python backend/manage.py migrate
```

This creates the Django tables and the application's tables, including the data required for:

- Users
- Words
- Game sessions
- Guesses
- Daily hint quotas
- Django admin/session data

If the migration completes successfully, you should see messages such as:

```text
Applying ... OK
```

---

## 7. Start the Backend Server

From the project root:

```powershell
python backend/manage.py runserver 8000
```

The backend should be available at:

```text
http://127.0.0.1:8000/
```

The frontend communicates with the API through:

```text
http://localhost:8000/api
```

Keep this terminal running.

### Backend terminal

```text
Terminal 1
└── python backend/manage.py runserver 8000
```

Do not close it while playing the game.

---

## 8. Configure the Frontend

Open a **second terminal**.

Move to the frontend:

```powershell
cd frontend
```

The frontend uses the Vite environment variable:

```env
VITE_API_BASE_URL=http://localhost:8000/api
```

---

## 9. Install Frontend Dependencies

Inside `frontend/`:

```powershell
npm install
```

This installs the React/Vite dependencies from `package.json`.

---

## 10. Start the Frontend

Still inside `frontend/`:

```powershell
npm run dev
```

Vite is configured to use port **5173**.

Open:

```text
http://localhost:5173
```

### Running setup

You should now have two terminals:

```text
Terminal 1 — Backend
python backend/manage.py runserver 8000

Terminal 2 — Frontend
cd frontend
npm run dev
```

Then open:

```text
http://localhost:5173
```

---

## 11. Navigation: Login / Registration

When the frontend opens for the first time, the application checks whether a Django session already exists.

If you are not logged in, you are automatically taken to:

```text
/login
```

The login screen contains:

- **Username** field
- **Password** field
- **Log In** button
- **Create an account** link

---

## 12. Player Registration

Click:

```text
Create an account
```

You are taken to:

```text
/register
```

### 12.1 Username requirements

The username must:

- Contain at least **5 letters**
- Contain at least one **uppercase** letter
- Contain at least one **lowercase** letter
- Contain letters only

Examples:

```text
Vedika       valid
PlayerA      valid
player       invalid
PLAYER       invalid
Ab12x        invalid
```

### 12.2 Password requirements

The password must:

- Contain at least **5 characters**
- Contain at least one letter
- Contain at least one number
- Contain at least one of:
  - `$`
  - `%`
  - `*`

Example format:

```text
Game1$
```

### 12.3 Choose the account type

The registration screen provides two choices:

```text
( ) Player
( ) Admin
```

### Player

Select **Player** for the normal game experience.

### Admin

Select **Admin** to create a staff/superuser account with access to the Admin Dashboard.

> For a real production deployment, admin account creation should normally be restricted rather than exposed as a public self-service option. The current project intentionally exposes the choice in the registration UI.

Click the registration/submit button.

On successful registration, the application briefly shows a success message and redirects to the login screen.

---

## 13. Login Navigation

At `/login`, enter the registered credentials.

Click:

```text
Log In
```

The application checks the authenticated Django session.

The destination depends on the account role:

```text
Player account
    ↓
/game

Admin account
    ↓
/admin
```

The session cookie is retained, so refreshing the page can restore the logged-in state.

---

## 14. Automatic Word Seeding

The game contains a built-in starter word list.

The first time a game is started, the backend checks whether the `Word` table is empty.

If it is empty, the starter words are inserted automatically.

The supplied starter list includes categories such as:

- Animals
- Flowers
- Food
- Nature
- Adjectives
- Places
- Objects
- Other

Each word is exactly five letters long.

Therefore, no separate manual word-import step is required for a fresh database.

---

## 15. Important Game Rules

| Rule | Current implementation |
|---|---|
| Word length | 5 letters |
| Maximum guesses | 5 per word |
| Games per player per day | 3 |
| Hints per player per day | 3 |
| Hint behavior | Reveals one letter and its position |
| Correct guess | Game becomes `won` |
| Fifth incorrect guess | Game becomes `lost` |
| Lost game | Answer is revealed |
| Guess storage | Persisted in database |
| Game session storage | Persisted in database |
| Word selection | Random active word |

---

## 16. Database Tables / Main Data Objects

The application stores the following important data.

### User

Django's built-in user model stores:

- Username
- Password hash
- Staff/admin status
- Authentication/session-related information

### Word

Stores:

- Five-letter word
- Category
- Active/inactive state

### GameSession

Stores:

- Player
- Selected word
- Status
- Date played
- Creation time
- Finish time
- Hint positions

### Guess

Stores:

- Game session
- Guess number
- Guess text
- Feedback for each letter
- Creation time

### UserHintQuota

Stores the number of hints a user has used for the current date.

---