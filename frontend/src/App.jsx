import axios from 'axios'
import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react'
import { BrowserRouter, Link, Navigate, Route, Routes, useNavigate } from 'react-router-dom'

const WORD_LENGTH = 5
const USERNAME_RE = /^(?=.*[a-z])(?=.*[A-Z])[A-Za-z]{5,}$/
const PASSWORD_RE = /^(?=.*[A-Za-z])(?=.*\d)(?=.*[$%*]).{5,}$/




const BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8000/api'
const client = axios.create({ baseURL: BASE_URL, withCredentials: true })

function getCookie(name) {
  const match = document.cookie.match(new RegExp(`(?:^|; )${name}=([^;]*)`))
  return match ? decodeURIComponent(match[1]) : null
}

client.interceptors.request.use((config) => {
  const method = (config.method || 'get').toUpperCase()
  if (!['GET', 'HEAD', 'OPTIONS'].includes(method)) {
    const csrftoken = getCookie('csrftoken')
    if (csrftoken) config.headers['X-CSRFToken'] = csrftoken
  }
  return config
})

async function ensureCsrfCookie() {
  await client.get('/auth/csrf/')
}



const AuthContext = createContext(null)

function AuthProvider({ children }) {
  const [username, setUsername] = useState(null)
  const [isAdmin, setIsAdmin] = useState(false)
  const [checkingSession, setCheckingSession] = useState(true)

  useEffect(() => {
    (async () => {
      try {
        await ensureCsrfCookie()
        const { data } = await client.get('/auth/me/')
        setUsername(data.username)
        setIsAdmin(data.is_admin)
      } catch {

      } finally {
        setCheckingSession(false)
      }
    })()
  }, [])

  const login = async (usernameInput, password) => {
    await ensureCsrfCookie()
    const { data } = await client.post('/auth/login/', { username: usernameInput, password })
    setUsername(data.username)
    setIsAdmin(data.is_admin)
    return data
  }

  const register = async (usernameInput, password, isAdmin) => {
    await ensureCsrfCookie()
    const { data } = await client.post('/auth/register/', { username: usernameInput, password, is_admin: isAdmin })
    return data
  }

  const logout = async () => {
    try {
      await client.post('/auth/logout/')
    } finally {
      setUsername(null)
      setIsAdmin(false)
    }
  }

  const value = useMemo(
    () => ({
      username,
      isAdmin,
      isAuthenticated: Boolean(username),
      checkingSession,
      login,
      register,
      logout,
    }),
    [username, isAdmin, checkingSession],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

function useAuth() {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth must be used within an AuthProvider')
  return ctx
}

function ProtectedRoute({ children, adminOnly = false }) {
  const { isAuthenticated, isAdmin, checkingSession } = useAuth()
  if (checkingSession) return <p className="status-text">Loading…</p>
  if (!isAuthenticated) return <Navigate to="/login" replace />
  if (adminOnly && !isAdmin) return <Navigate to="/game" replace />
  return children
}



function Tile({ letter, state, animate = false, delay = 0 }) {
  return (
    <div className={`tile tile--${state}`} style={animate ? { animationDelay: `${delay}ms` } : undefined}>
      {letter}
    </div>
  )
}

function GameBoard({ guesses, maxGuesses, currentGuess, justSubmittedRow }) {
  const rows = []

  guesses.forEach((guess, rowIndex) => {
    rows.push(
      <div className="board-row" key={`guess-${rowIndex}`}>
        {guess.text.split('').map((letter, i) => (
          <Tile key={i} letter={letter} state={guess.feedback[i]} animate={rowIndex === justSubmittedRow} delay={i * 150} />
        ))}
      </div>,
    )
  })

  if (guesses.length < maxGuesses) {
    const chars = currentGuess.padEnd(WORD_LENGTH, ' ').split('')
    rows.push(
      <div className="board-row" key="current">
        {chars.map((letter, i) => (
          <Tile key={i} letter={letter.trim()} state={letter.trim() ? 'filled' : 'empty'} />
        ))}
      </div>,
    )
  }

  for (let i = rows.length; i < maxGuesses; i += 1) {
    rows.push(
      <div className="board-row" key={`empty-${i}`}>
        {Array.from({ length: WORD_LENGTH }).map((_, j) => (
          <Tile key={j} letter="" state="empty" />
        ))}
      </div>,
    )
  }

  return <div className="board">{rows}</div>
}

const KEYBOARD_ROWS = [
  ['Q', 'W', 'E', 'R', 'T', 'Y', 'U', 'I', 'O', 'P'],
  ['A', 'S', 'D', 'F', 'G', 'H', 'J', 'K', 'L'],
  ['ENTER', 'Z', 'X', 'C', 'V', 'B', 'N', 'M', 'BACKSPACE'],
]
const RANK = { green: 3, orange: 2, grey: 1 }

function computeLetterStates(guesses) {
  const states = {}
  guesses.forEach((guess) => {
    guess.text.split('').forEach((letter, i) => {
      const state = guess.feedback[i]
      if (!states[letter] || RANK[state] > RANK[states[letter]]) states[letter] = state
    })
  })
  return states
}

function Keyboard({ guesses, onKey, disabled }) {
  const letterStates = computeLetterStates(guesses)
  return (
    <div className="keyboard">
      {KEYBOARD_ROWS.map((row, i) => (
        <div className="keyboard-row" key={i}>
          {row.map((key) => {
            const isSpecial = key === 'ENTER' || key === 'BACKSPACE'
            const state = letterStates[key]
            return (
              <button
                key={key}
                type="button"
                disabled={disabled}
                className={`key ${isSpecial ? 'key--wide' : ''} ${state ? `key--${state}` : ''}`}
                onClick={() => onKey(key)}
              >
                {key === 'BACKSPACE' ? '⌫' : key}
              </button>
            )
          })}
        </div>
      ))}
    </div>
  )
}



function Login() {
  const { login } = useAuth()
  const navigate = useNavigate()
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)

  const handleSubmit = async (e) => {
    e.preventDefault()
    setError('')
    setLoading(true)
    try {
      const data = await login(username, password)
      navigate(data.is_admin ? '/admin' : '/game')
    } catch (err) {
      setError(err.response?.data?.detail || 'Invalid username or password.')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="auth-page">
      <div className="auth-card">
        <h1 className="brand">GUESS <span className="brand-accent">THE WORD</span></h1>
        <p className="auth-subtitle">Log in to keep guessing.</p>
        <form onSubmit={handleSubmit} className="auth-form">
          <label>Username
            <input value={username} onChange={(e) => setUsername(e.target.value)} required autoFocus />
          </label>
          <label>Password
            <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} required />
          </label>
          {error && <p className="form-error">{error}</p>}
          <button type="submit" className="btn btn--primary" disabled={loading}>
            {loading ? 'Logging in…' : 'Log In'}
          </button>
        </form>
        <p className="auth-footer">New here? <Link to="/register">Create an account</Link></p>
      </div>
    </div>
  )
}

function Register() {
  const { register } = useAuth()
  const navigate = useNavigate()
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [isAdmin, setIsAdmin] = useState(false)
  const [error, setError] = useState('')
  const [success, setSuccess] = useState(false)
  const [loading, setLoading] = useState(false)

  const usernameValid = USERNAME_RE.test(username)
  const passwordValid = PASSWORD_RE.test(password)

  const handleSubmit = async (e) => {
    e.preventDefault()
    setError('')
    if (!usernameValid || !passwordValid) {
      setError('Please fix the highlighted requirements below.')
      return
    }
    setLoading(true)
    try {
      await register(username, password, isAdmin)
      setSuccess(true)
      setTimeout(() => navigate('/login'), 1200)
    } catch (err) {
      const data = err.response?.data
      setError(data ? Object.values(data).flat().join(' ') : 'Registration failed.')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="auth-page">
      <div className="auth-card">
        <h1 className="brand">GUESS <span className="brand-accent">THE WORD</span></h1>
        <p className="auth-subtitle">Create a player account.</p>
        <form onSubmit={handleSubmit} className="auth-form">
          <label>Username
            <input value={username} onChange={(e) => setUsername(e.target.value)} required autoFocus />
          </label>
          <p className={`hint ${username && !usernameValid ? 'hint--bad' : username ? 'hint--good' : ''}`}>
            At least 5 letters, with both upper &amp; lower case.
          </p>
          <label>Password
            <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} required />
          </label>
          <p className={`hint ${password && !passwordValid ? 'hint--bad' : password ? 'hint--good' : ''}`}>
            At least 5 characters, with a letter, a number, and one of $ % *
          </p>
          <div className="form-group" style={{ display: 'flex', gap: '1rem', margin: '1rem 0' }}>
            <label style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', fontWeight: 'normal' }}>
              <input type="radio" checked={!isAdmin} onChange={() => setIsAdmin(false)} />
              Player
            </label>
            <label style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', fontWeight: 'normal' }}>
              <input type="radio" checked={isAdmin} onChange={() => setIsAdmin(true)} />
              Admin
            </label>
          </div>
          {error && <p className="form-error">{error}</p>}
          {success && <p className="form-success">Account created! Redirecting to login…</p>}
          <button type="submit" className="btn btn--primary" disabled={loading}>
            {loading ? 'Creating account…' : 'Register'}
          </button>
        </form>
        <p className="auth-footer">Already have an account? <Link to="/login">Log in</Link></p>
      </div>
    </div>
  )
}

function Game() {
  const { username, logout } = useAuth()
  const [session, setSession] = useState(null)
  const [currentGuess, setCurrentGuess] = useState('')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(true)
  const [submitting, setSubmitting] = useState(false)
  const [modal, setModal] = useState(null)
  const [justSubmittedRow, setJustSubmittedRow] = useState(-1)
  const [blocked, setBlocked] = useState('')
  const [showHelp, setShowHelp] = useState(() => !localStorage.getItem('gtw_help_seen'))
  const [hintsLeft, setHintsLeft] = useState(3)
  const [hintMsg, setHintMsg] = useState('')

  const startGame = useCallback(async () => {
    setLoading(true)
    setError('')
    setBlocked('')
    setHintMsg('')
    try {
      const { data } = await client.post('/game/start/')
      setSession(data)
      if (data.hints_remaining !== undefined) setHintsLeft(data.hints_remaining)
      if (data.status === 'won') setModal('won')
      else if (data.status === 'lost') setModal('lost')
    } catch (err) {
      setBlocked(err.response?.data?.detail || 'Could not start a new game.')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    startGame()
  }, [startGame])

  const submitGuess = useCallback(async () => {
    if (currentGuess.length !== WORD_LENGTH || submitting || !session) return
    setSubmitting(true)
    setError('')
    try {
      const { data } = await client.post(`/game/${session.id}/guess/`, { guess: currentGuess })
      setJustSubmittedRow(data.guesses.length - 1)
      setSession(data)
      if (data.hints_remaining !== undefined) setHintsLeft(data.hints_remaining)
      setCurrentGuess('')
      if (data.status === 'won') setTimeout(() => setModal('won'), 5 * 150 + 300)
      else if (data.status === 'lost') setTimeout(() => setModal('lost'), 5 * 150 + 300)
    } catch (err) {
      setError(err.response?.data?.detail || 'Something went wrong.')
    } finally {
      setSubmitting(false)
    }
  }, [currentGuess, session, submitting])

  const requestHint = useCallback(async () => {
    if (!session || session.status !== 'in_progress' || hintsLeft <= 0) return
    try {
      const { data } = await client.post(`/game/${session.id}/hint/`)
      setHintsLeft(data.hints_remaining)
      setHintMsg(`Position ${data.position + 1} is the letter "${data.letter}"`)
    } catch (err) {
      setHintMsg(err.response?.data?.detail || 'Could not get a hint.')
    }
  }, [session, hintsLeft])

  const handleKey = useCallback(
    (key) => {
      if (!session || session.status !== 'in_progress' || submitting) return
      if (key === 'ENTER') submitGuess()
      else if (key === 'BACKSPACE') setCurrentGuess((g) => g.slice(0, -1))
      else if (/^[A-Z]$/.test(key) && currentGuess.length < WORD_LENGTH) setCurrentGuess((g) => g + key)
    },
    [session, submitting, currentGuess, submitGuess],
  )

  useEffect(() => {
    const onKeyDown = (e) => {
      if (e.key === 'Enter') handleKey('ENTER')
      else if (e.key === 'Backspace') handleKey('BACKSPACE')
      else if (/^[a-zA-Z]$/.test(e.key)) handleKey(e.key.toUpperCase())
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [handleKey])

  const closeModalAndRestart = () => {
    setModal(null)
    setCurrentGuess('')
    setJustSubmittedRow(-1)
    setHintMsg('')
    startGame()
  }

  const closeHelp = () => {
    localStorage.setItem('gtw_help_seen', '1')
    setShowHelp(false)
  }

  return (
    <div className="game-page">
      <header className="topbar">
        <h1 className="brand brand--small">GUESS <span className="brand-accent">THE WORD</span></h1>
        <div className="topbar-right">
          <span className="username-pill">&#x1F464; {username}</span>
          <button className="btn btn--ghost help-btn" onClick={() => setShowHelp(true)} title="How to play">?</button>
          <button className="btn btn--ghost" onClick={logout}>Log out</button>
        </div>
      </header>

      <main className="game-main">
        {loading && <p className="status-text">Loading&hellip;</p>}
        {!loading && blocked && (
          <div className="notice notice--warn"><p>{blocked}</p></div>
        )}
        {!loading && session && !blocked && (
          <>
            {session.word?.category && (
              <div className="category-badge">
                <span className="category-label">Category:&nbsp;</span>
                <span className="category-value">{session.word.category.charAt(0).toUpperCase() + session.word.category.slice(1)}</span>
              </div>
            )}
            <GameBoard
              guesses={session.guesses}
              maxGuesses={session.max_guesses}
              currentGuess={currentGuess}
              justSubmittedRow={justSubmittedRow}
            />
            {error && <p className="form-error center">{error}</p>}
            <Keyboard guesses={session.guesses} onKey={handleKey} disabled={submitting || session.status !== 'in_progress'} />
            <div className="hint-row">
              <button
                className="btn btn--ghost hint-btn"
                onClick={requestHint}
                disabled={hintsLeft <= 0 || session.status !== 'in_progress' || submitting}
              >
                &#x1F4A1; Hint ({hintsLeft} left today)
              </button>
              {hintMsg && <p className="hint-msg">{hintMsg}</p>}
            </div>
            <p className="attempts-text">
              Attempt {session.attempts_used} of {session.max_guesses}
            </p>
          </>
        )}
      </main>

      {modal && (
        <div className="modal-backdrop">
          <div className="modal-card">
            {modal === 'won' ? (
              <>
                <h2>&#x1F389; Congratulations!</h2>
                <p>You guessed the word correctly.</p>
              </>
            ) : (
              <>
                <h2>Better luck next time!</h2>
                <p>You used all your guesses for this word.</p>
                {session?.word?.text && <p className="revealed-word">The word was: <strong>{session.word.text}</strong></p>}
              </>
            )}
            <button className="btn btn--primary" onClick={closeModalAndRestart}>Play Again</button>
          </div>
        </div>
      )}

      {showHelp && (
        <div className="modal-backdrop">
          <div className="modal-card help-modal">
            <h2>How to Play</h2>
            <div className="help-body">
              <p>Guess the hidden <strong>5-letter word</strong> in up to <strong>5 attempts</strong>.</p>
              <p>After each guess, tiles change colour to show how close you were:</p>
              <ul className="help-legend">
                <li><span className="help-tile help-tile--green">A</span>Right letter, right position</li>
                <li><span className="help-tile help-tile--orange">B</span>Right letter, wrong position</li>
                <li><span className="help-tile help-tile--grey">C</span>Letter not in the word</li>
              </ul>
              <p>&#x1F4A1; You get <strong>3 hints per day</strong> &mdash; each reveals one letter&apos;s exact position.</p>
              <p>&#x1F501; Play as many words as you like! If you run out of guesses, the answer is revealed.</p>
            </div>
            <button className="btn btn--primary" onClick={closeHelp}>Got it!</button>
          </div>
        </div>
      )}
    </div>
  )
}

function todayISO() {
  return new Date().toISOString().slice(0, 10)
}

function AdminDashboard() {
  const { username, logout } = useAuth()
  const [date, setDate] = useState(todayISO())
  const [dailyReport, setDailyReport] = useState(null)
  const [dailyError, setDailyError] = useState('')
  const [users, setUsers] = useState([])
  const [selectedUserId, setSelectedUserId] = useState('')
  const [userReport, setUserReport] = useState(null)
  const [userError, setUserError] = useState('')

  useEffect(() => {
    client.get('/users/').then(({ data }) => setUsers(data.results)).catch(() => setUsers([]))
  }, [])

  const fetchDailyReport = async (targetDate) => {
    setDailyError('')
    try {
      const { data } = await client.get('/reports/daily/', { params: { date: targetDate } })
      setDailyReport(data)
    } catch (err) {
      setDailyError(err.response?.data?.detail || 'Could not load the daily report.')
    }
  }

  useEffect(() => {
    fetchDailyReport(date)

  }, [])

  const fetchUserReport = async (userId) => {
    if (!userId) {
      setUserReport(null)
      return
    }
    setUserError('')
    try {
      const { data } = await client.get(`/reports/user/${userId}/`)
      setUserReport(data)
    } catch (err) {
      setUserError(err.response?.data?.detail || 'Could not load the user report.')
    }
  }

  return (
    <div className="game-page">
      <header className="topbar">
        <h1 className="brand brand--small">ADMIN <span className="brand-accent">DASHBOARD</span></h1>
        <div className="topbar-right">
          <span className="username-pill">🛠️ {username}</span>
          <button className="btn btn--ghost" onClick={logout}>Log out</button>
        </div>
      </header>

      <main className="admin-main">
        <section className="card">
          <h2>Daily Report</h2>
          <div className="report-controls">
            <label>Date
              <input
                type="date"
                value={date}
                onChange={(e) => {
                  setDate(e.target.value)
                  fetchDailyReport(e.target.value)
                }}
              />
            </label>
          </div>
          {dailyError && <p className="form-error">{dailyError}</p>}
          {dailyReport && (
            <div className="stat-row">
              <div className="stat">
                <span className="stat-value">{dailyReport.num_users}</span>
                <span className="stat-label">Users played</span>
              </div>
              <div className="stat">
                <span className="stat-value">{dailyReport.num_correct_guesses}</span>
                <span className="stat-label">Correct guesses</span>
              </div>
            </div>
          )}
        </section>

        <section className="card">
          <h2>User Report</h2>
          <div className="report-controls">
            <label>Player
              <select
                value={selectedUserId}
                onChange={(e) => {
                  setSelectedUserId(e.target.value)
                  fetchUserReport(e.target.value)
                }}
              >
                <option value="">Select a player…</option>
                {users.map((u) => (
                  <option key={u.id} value={u.id}>{u.username}</option>
                ))}
              </select>
            </label>
          </div>
          {userError && <p className="form-error">{userError}</p>}
          {userReport && (
            <table className="report-table">
              <thead>
                <tr><th>Date</th><th>Words tried</th><th>Correct guesses</th></tr>
              </thead>
              <tbody>
                {userReport.report.length === 0 && (
                  <tr><td colSpan={3} className="empty-row">No games played yet.</td></tr>
                )}
                {userReport.report.map((row) => (
                  <tr key={row.date}>
                    <td>{row.date}</td>
                    <td>{row.words_tried}</td>
                    <td>{row.correct_guesses}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </section>
      </main>
    </div>
  )
}



function Routing() {
  const { isAuthenticated, isAdmin, checkingSession } = useAuth()
  return (
    <Routes>
      <Route
        path="/"
        element={
          checkingSession ? (
            <p className="status-text">Loading…</p>
          ) : isAuthenticated ? (
            <Navigate to={isAdmin ? '/admin' : '/game'} replace />
          ) : (
            <Navigate to="/login" replace />
          )
        }
      />
      <Route path="/login" element={<Login />} />
      <Route path="/register" element={<Register />} />
      <Route path="/game" element={<ProtectedRoute><Game /></ProtectedRoute>} />
      <Route path="/admin" element={<ProtectedRoute adminOnly><AdminDashboard /></ProtectedRoute>} />
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  )
}

export default function App() {
  return (
    <BrowserRouter>
      <AuthProvider>
        <Routing />
      </AuthProvider>
    </BrowserRouter>
  )
}
