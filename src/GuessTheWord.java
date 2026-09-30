import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.swing.*;
import java.awt.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Pattern;

public class GuessTheWord {

    // =====================================================================================
    //  Game rules
    // =====================================================================================
    static final int WORD_LENGTH = 5;
    static final int MAX_GUESSES = 5;
    static final int MAX_GAMES_PER_DAY = 3;
    static final int MAX_HINTS_PER_DAY = 3;

    static final Pattern USERNAME_RE = Pattern.compile("^(?=.*[a-z])(?=.*[A-Z])[A-Za-z]{5,}$");
    static final Pattern PASSWORD_RE = Pattern.compile("^(?=.*[A-Za-z])(?=.*\\d)(?=.*[$%*]).{5,}$");

    static final String[][] STARTER_WORDS = {
            {"TIGER", "animal"}, {"EAGLE", "animal"}, {"SHARK", "animal"}, {"BISON", "animal"}, {"KOALA", "animal"},
            {"TULIP", "flower"}, {"LILAC", "flower"}, {"DAISY", "flower"}, {"PANSY", "flower"}, {"LOTUS", "flower"},
            {"BREAD", "food"}, {"GRAPE", "food"}, {"OLIVE", "food"}, {"MANGO", "food"}, {"PIZZA", "food"},
            {"RIVER", "nature"}, {"CLOUD", "nature"}, {"OCEAN", "nature"}, {"STORM", "nature"}, {"FLAME", "nature"},
            {"BRAVE", "adjective"}, {"LIGHT", "adjective"}, {"SWEET", "adjective"}, {"SHARP", "adjective"}, {"CRISP", "adjective"},
            {"PLAZA", "place"}, {"TOWER", "place"}, {"ARENA", "place"}, {"DEPOT", "place"}, {"HAVEN", "place"},
            {"CHAIR", "object"}, {"CLOCK", "object"}, {"BRUSH", "object"}, {"PIANO", "object"}, {"TORCH", "object"},
            {"DREAM", "other"}, {"SMILE", "other"}, {"HEART", "other"}, {"MUSIC", "other"}, {"WORLD", "other"},
    };

    static List<String> scoreGuess(String guess, String answer) {
        String[] result = new String[WORD_LENGTH];
        char[] remaining = answer.toCharArray();
        for (int i = 0; i < WORD_LENGTH; i++) {
            if (guess.charAt(i) == answer.charAt(i)) {
                result[i] = "green";
                remaining[i] = '\0';
            }
        }
        for (int i = 0; i < WORD_LENGTH; i++) {
            if (result[i] != null) continue;
            int idx = -1;
            for (int j = 0; j < remaining.length; j++) {
                if (remaining[j] == guess.charAt(i)) { idx = j; break; }
            }
            if (idx >= 0) {
                result[i] = "orange";
                remaining[idx] = '\0';
            } else {
                result[i] = "grey";
            }
        }
        return new ArrayList<>(List.of(result));
    }

    // =====================================================================================
    //  Simple data classes
    // =====================================================================================
    static class GameException extends Exception {
        GameException(String message) { super(message); }
    }

    record User(long id, String username, boolean staff) { }

    record GuessRow(int number, String text, List<String> feedback) { }

    record Player(long id, String username) {
        @Override public String toString() { return username; }
    }

    record HintResult(int position, char letter, int hintsRemaining) { }

    static class Session {
        long id, userId;
        String status, category, answer, createdAt;
        LocalDate playedOn;
        List<Integer> hints = new ArrayList<>();
        List<GuessRow> guesses = new ArrayList<>();
        int hintsRemaining;

        boolean over() { return !status.equals("in_progress"); }
        boolean lost() { return status.equals("lost"); }
        boolean won() { return status.equals("won"); }
    }

    static final String SALT_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    static String hashPassword(String raw) {
        SecureRandom rnd = new SecureRandom();
        StringBuilder salt = new StringBuilder();
        for (int i = 0; i < 22; i++) salt.append(SALT_CHARS.charAt(rnd.nextInt(SALT_CHARS.length())));
        return pbkdf2(raw, salt.toString(), 720_000);
    }

    static boolean checkPassword(String raw, String stored) {
        if (raw == null || raw.isEmpty() || stored == null) return false;
        String[] p = stored.split("\\$", 4);
        if (p.length != 4 || !p[0].equals("pbkdf2_sha256")) return false;
        try {
            String again = pbkdf2(raw, p[2], Integer.parseInt(p[1]));
            return MessageDigest.isEqual(again.getBytes(StandardCharsets.UTF_8), stored.getBytes(StandardCharsets.UTF_8));
        } catch (NumberFormatException e) {
            return false;
        }
    }

    static String pbkdf2(String raw, String salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(raw.toCharArray(), salt.getBytes(StandardCharsets.UTF_8), iterations, 256);
            byte[] key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            return "pbkdf2_sha256$" + iterations + "$" + salt + "$" + Base64.getEncoder().encodeToString(key);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    // =====================================================================================
    //  Database layer
    // =====================================================================================
    static class Db {
        static Connection conn;
        static final Random random = new Random();

        interface Work<T> { T run() throws Exception; }

        static LocalDate today() { return LocalDate.now(ZoneOffset.UTC); }   // Django ran in UTC

        // ---- settings: environment variables, then ".env" file --------------------------
        static Map<String, String> settings() {
            Map<String, String> m = new HashMap<>();
            Path env = Paths.get(".env");
            try {
                if (Files.exists(env)) {
                    for (String line : Files.readAllLines(env)) {
                        line = line.trim();
                        int eq = line.indexOf('=');
                        if (line.isEmpty() || line.startsWith("#") || eq < 1) continue;
                        m.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim().replaceAll("^['\"]|['\"]$", ""));
                    }
                }
            } catch (Exception ignored) { }
            m.putAll(System.getenv());          // real environment variables win
            return m;
        }

        static void connect() throws SQLException {
            Map<String, String> s = settings();
            String url = "jdbc:postgresql://" + s.getOrDefault("DB_HOST", "localhost") + ":"
                    + s.getOrDefault("DB_PORT", "5432") + "/" + s.getOrDefault("DB_NAME", "capstone_db");
            conn = DriverManager.getConnection(url, s.getOrDefault("DB_USER", "postgres"),
                    s.getOrDefault("DB_PASSWORD", "postgres"));
        }

        static List<Object[]> query(String sql, Object... args) throws SQLException {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
                try (ResultSet rs = ps.executeQuery()) {
                    int cols = rs.getMetaData().getColumnCount();
                    List<Object[]> rows = new ArrayList<>();
                    while (rs.next()) {
                        Object[] row = new Object[cols];
                        for (int c = 0; c < cols; c++) row[c] = rs.getObject(c + 1);
                        rows.add(row);
                    }
                    return rows;
                }
            }
        }

        static void exec(String sql, Object... args) throws SQLException {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
                ps.executeUpdate();
            }
        }

        static long num(Object o) { return ((Number) o).longValue(); }

        static synchronized <T> T tx(Work<T> work) throws GameException {
            try {
                conn.setAutoCommit(false);
                T result = work.run();
                conn.commit();
                return result;
            } catch (GameException e) {
                rollback();
                throw e;
            } catch (Exception e) {
                rollback();
                throw new GameException("Database error: " + e.getMessage());
            } finally {
                try { conn.setAutoCommit(true); } catch (SQLException ignored) { }
            }
        }

        static void rollback() {
            try { conn.rollback(); } catch (SQLException ignored) { }
        }

        static String toJson(List<?> items) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < items.size(); i++) {
                if (i > 0) sb.append(',');
                Object o = items.get(i);
                sb.append(o instanceof Number ? o.toString() : "\"" + o + "\"");
            }
            return sb.append(']').toString();
        }

        static List<String> parseJsonList(String json) {
            List<String> out = new ArrayList<>();
            String body = json == null ? "" : json.trim().replaceAll("^\\[|\\]$", "").trim();
            if (body.isEmpty()) return out;
            for (String part : body.split(",")) out.add(part.trim().replace("\"", ""));
            return out;
        }

        static void register(String rawUsername, String password, boolean admin) throws GameException {
            final String username = rawUsername.strip();
            if (!USERNAME_RE.matcher(username).matches())
                throw new GameException("Username must be at least 5 letters long and contain both upper case and lower case letters.");
            tx(() -> {
                if (!query("SELECT 1 FROM auth_user WHERE lower(username) = lower(?)", username).isEmpty())
                    throw new GameException("That username is already taken.");
                if (!PASSWORD_RE.matcher(password).matches())
                    throw new GameException("Password must be at least 5 characters long and contain a letter, a number, and one of the special characters $, %, *.");
                exec("INSERT INTO auth_user (password, is_superuser, username, first_name, last_name, email, is_staff, is_active, date_joined) "
                        + "VALUES (?, ?, ?, '', '', '', ?, TRUE, now())", hashPassword(password), admin, username, admin);
                return null;
            });
        }

        static User login(String username, String password) throws GameException {
            return tx(() -> {
                List<Object[]> rows = query("SELECT id, password, is_staff, is_active FROM auth_user WHERE username = ?", username);
                if (rows.isEmpty() || !(Boolean) rows.get(0)[3] || !checkPassword(password, (String) rows.get(0)[1]))
                    throw new GameException("Invalid username or password.");
                long id = num(rows.get(0)[0]);
                exec("UPDATE auth_user SET last_login = now() WHERE id = ?", id);
                return new User(id, username, (Boolean) rows.get(0)[2]);
            });
        }

        static Session startGame(long userId) throws GameException {
            return tx(() -> {
                if (num(query("SELECT count(*) FROM backend_word").get(0)[0]) == 0) {
                    for (String[] w : STARTER_WORDS)
                        exec("INSERT INTO backend_word (text, category, is_active) VALUES (?, ?, TRUE) ON CONFLICT (text) DO NOTHING", w[0], w[1]);
                }
                List<Object[]> active = query("SELECT id FROM backend_gamesession WHERE user_id = ? AND status = 'in_progress' ORDER BY id LIMIT 1", userId);
                if (!active.isEmpty()) return load(num(active.get(0)[0]), userId);

                if (num(query("SELECT count(*) FROM backend_gamesession WHERE user_id = ? AND played_on = ?", userId, today()).get(0)[0]) >= MAX_GAMES_PER_DAY)
                    throw new GameException("You have already played 3 games today. Come back tomorrow!");
                List<Object[]> word = query("SELECT id FROM backend_word WHERE is_active = TRUE ORDER BY random() LIMIT 1");
                if (word.isEmpty()) throw new GameException("No words are configured yet. Please contact an admin.");

                long id = num(query("INSERT INTO backend_gamesession (user_id, word_id, status, played_on, created_at, hints_given) "
                        + "VALUES (?, ?, 'in_progress', ?, now(), '[]'::jsonb) RETURNING id", userId, num(word.get(0)[0]), today()).get(0)[0]);
                return load(id, userId);
            });
        }

        static Session getSession(long sessionId, long userId) throws GameException {
            return tx(() -> load(sessionId, userId));
        }

        static List<Session> history(long userId) throws GameException {
            return tx(() -> {
                List<Session> out = new ArrayList<>();
                for (Object[] r : query("SELECT id FROM backend_gamesession WHERE user_id = ? ORDER BY created_at DESC", userId))
                    out.add(load(num(r[0]), userId));
                return out;
            });
        }

        static Session submitGuess(long userId, long sessionId, String rawGuess) throws GameException {
            final String guess = rawGuess.strip().toUpperCase(Locale.ROOT);
            if (guess.length() != WORD_LENGTH || !guess.chars().allMatch(Character::isLetter))
                throw new GameException("Your guess must be exactly 5 letters.");
            return tx(() -> {
                Session s = load(sessionId, userId);
                if (s.over()) throw new GameException("This game has already ended.");
                if (s.guesses.size() >= MAX_GUESSES) throw new GameException("No guesses remaining.");

                int number = s.guesses.size() + 1;
                exec("INSERT INTO backend_guess (session_id, guess_number, text, feedback, created_at) VALUES (?, ?, ?, ?::jsonb, now())",
                        sessionId, number, guess, toJson(scoreGuess(guess, s.answer)));
                if (guess.equals(s.answer)) {
                    exec("UPDATE backend_gamesession SET status = 'won', finished_at = now() WHERE id = ?", sessionId);
                } else if (number >= MAX_GUESSES) {
                    exec("UPDATE backend_gamesession SET status = 'lost', finished_at = now() WHERE id = ?", sessionId);
                }
                return load(sessionId, userId);
            });
        }

        static HintResult hint(long userId, long sessionId) throws GameException {
            return tx(() -> {
                List<Object[]> q = query("SELECT date, count FROM backend_userhintquota WHERE user_id = ?", userId);
                int used;
                if (q.isEmpty()) {
                    exec("INSERT INTO backend_userhintquota (user_id, date, count) VALUES (?, ?, 0)", userId, today());
                    used = 0;
                } else {
                    boolean sameDay = ((java.sql.Date) q.get(0)[0]).toLocalDate().equals(today());
                    used = sameDay ? (int) num(q.get(0)[1]) : 0;
                }
                if (used >= MAX_HINTS_PER_DAY) throw new GameException("You have used all 3 hints for today. Come back tomorrow!");

                Session s = load(sessionId, userId);
                if (s.over()) throw new GameException("This game has already ended.");

                Set<Integer> revealed = new HashSet<>(s.hints);
                for (GuessRow g : s.guesses)
                    for (int i = 0; i < g.feedback().size(); i++)
                        if (g.feedback().get(i).equals("green")) revealed.add(i);
                List<Integer> unrevealed = new ArrayList<>();
                for (int i = 0; i < WORD_LENGTH; i++) if (!revealed.contains(i)) unrevealed.add(i);
                if (unrevealed.isEmpty()) throw new GameException("All letters are already revealed!");

                int pos = unrevealed.get(random.nextInt(unrevealed.size()));
                s.hints.add(pos);
                exec("UPDATE backend_gamesession SET hints_given = ?::jsonb WHERE id = ?", toJson(s.hints), sessionId);
                exec("UPDATE backend_userhintquota SET date = ?, count = ? WHERE user_id = ?", today(), used + 1, userId);
                return new HintResult(pos, s.answer.charAt(pos), MAX_HINTS_PER_DAY - (used + 1));
            });
        }

        static Session load(long sessionId, long userId) throws Exception {
            List<Object[]> rows = query("SELECT s.id, s.user_id, s.status, s.played_on, s.created_at, s.hints_given::text, w.text, w.category "
                    + "FROM backend_gamesession s JOIN backend_word w ON w.id = s.word_id WHERE s.id = ? AND s.user_id = ?", sessionId, userId);
            if (rows.isEmpty()) throw new GameException("Game not found.");
            Object[] r = rows.get(0);
            Session s = new Session();
            s.id = num(r[0]);
            s.userId = num(r[1]);
            s.status = (String) r[2];
            s.playedOn = ((java.sql.Date) r[3]).toLocalDate();
            s.createdAt = String.valueOf(r[4]);
            for (String h : parseJsonList((String) r[5])) s.hints.add(Integer.parseInt(h));
            s.answer = (String) r[6];
            s.category = (String) r[7];
            for (Object[] g : query("SELECT guess_number, text, feedback::text FROM backend_guess WHERE session_id = ? ORDER BY guess_number", sessionId))
                s.guesses.add(new GuessRow((int) num(g[0]), (String) g[1], parseJsonList((String) g[2])));

            List<Object[]> q = query("SELECT date, count FROM backend_userhintquota WHERE user_id = ?", userId);
            s.hintsRemaining = MAX_HINTS_PER_DAY;
            if (!q.isEmpty() && ((java.sql.Date) q.get(0)[0]).toLocalDate().equals(today()))
                s.hintsRemaining = Math.max(0, MAX_HINTS_PER_DAY - (int) num(q.get(0)[1]));
            return s;
        }

        static long[] dailyReport(LocalDate day) throws GameException {
            return tx(() -> new long[]{
                    num(query("SELECT count(DISTINCT user_id) FROM backend_gamesession WHERE played_on = ?", day).get(0)[0]),
                    num(query("SELECT count(*) FROM backend_gamesession WHERE played_on = ? AND status = 'won'", day).get(0)[0])});
        }

        static List<String> userReport(long userId) throws GameException {
            return tx(() -> {
                List<String> lines = new ArrayList<>();
                for (Object[] r : query("SELECT played_on, count(*), count(*) FILTER (WHERE status = 'won') "
                        + "FROM backend_gamesession WHERE user_id = ? GROUP BY played_on ORDER BY played_on DESC", userId))
                    lines.add(((java.sql.Date) r[0]).toLocalDate() + "   words tried: " + num(r[1]) + "   correct guesses: " + num(r[2]));
                return lines;
            });
        }

        static List<Player> players() throws GameException {
            return tx(() -> {
                List<Player> out = new ArrayList<>();
                for (Object[] r : query("SELECT id, username FROM auth_user WHERE is_staff = FALSE ORDER BY username"))
                    out.add(new Player(num(r[0]), (String) r[1]));
                return out;
            });
        }
    }

    // =====================================================================================
    //  Swing user interface
    // =====================================================================================
    static final Color GREEN = new Color(0x6a, 0xaa, 0x64);
    static final Color ORANGE = new Color(0xe0, 0x8a, 0x1e);
    static final Color GREY = new Color(0x78, 0x7c, 0x7e);

    static class App extends JFrame {
        User user;
        Session session;

        final CardLayout cards = new CardLayout();
        final JPanel body = new JPanel(cards);
        final JLabel who = new JLabel();
        final JButton logoutBtn = new JButton("Logout");
        final JPanel topBar = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        Component main;

        // login / register
        final JTextField userField = new JTextField(16);
        final JPasswordField passField = new JPasswordField(16);
        final JCheckBox adminBox = new JCheckBox("Register as admin");

        // game
        final JPanel gamePanel = new JPanel(new BorderLayout(8, 8));
        final JLabel[][] cells = new JLabel[MAX_GUESSES][WORD_LENGTH];
        final JLabel info = new JLabel(" ", SwingConstants.CENTER);
        final JLabel pattern = new JLabel(" ", SwingConstants.CENTER);
        final JLabel hintsLabel = new JLabel(" ");
        final JTextField guessField = new JTextField(7);
        final JButton guessBtn = new JButton("Guess");
        final JButton hintBtn = new JButton("Hint");

        // admin
        final JPanel adminPanel = new JPanel(new BorderLayout(8, 8));
        final JTextField dateField = new JTextField(LocalDate.now(ZoneOffset.UTC).toString(), 10);
        final JLabel dailyResult = new JLabel(" ");
        final JComboBox<Player> playerBox = new JComboBox<>();
        final JTextArea userReportArea = new JTextArea(10, 40);

        App() {
            super("Guess the Word");
            setDefaultCloseOperation(EXIT_ON_CLOSE);
            buildTopBar();
            body.add(buildAuthPanel(), "auth");
            buildGamePanel();
            buildAdminPanel();
            setLayout(new BorderLayout());
            add(topBar, BorderLayout.NORTH);
            add(body, BorderLayout.CENTER);
            setSize(560, 640);
            setLocationRelativeTo(null);
            cards.show(body, "auth");
        }

        void buildTopBar() {
            logoutBtn.addActionListener(e -> logout());
            topBar.add(who);
            topBar.add(logoutBtn);
            topBar.setVisible(false);
        }

        JPanel buildAuthPanel() {
            JPanel p = new JPanel(new GridBagLayout());
            GridBagConstraints c = new GridBagConstraints();
            c.insets = new Insets(6, 6, 6, 6);
            c.gridx = 0; c.gridy = 0; c.gridwidth = 2;
            JLabel title = new JLabel("Guess the Word", SwingConstants.CENTER);
            title.setFont(title.getFont().deriveFont(Font.BOLD, 26f));
            p.add(title, c);
            c.gridwidth = 1; c.gridy = 1; p.add(new JLabel("Username"), c);
            c.gridx = 1; p.add(userField, c);
            c.gridx = 0; c.gridy = 2; p.add(new JLabel("Password"), c);
            c.gridx = 1; p.add(passField, c);
            c.gridx = 1; c.gridy = 3; p.add(adminBox, c);
            JButton login = new JButton("Login");
            JButton register = new JButton("Register");
            login.addActionListener(e -> doLogin());
            passField.addActionListener(e -> doLogin());
            register.addActionListener(e -> doRegister());
            JPanel buttons = new JPanel();
            buttons.add(login);
            buttons.add(register);
            c.gridx = 0; c.gridy = 4; c.gridwidth = 2; p.add(buttons, c);
            JLabel rules = new JLabel("<html><center><small>Username: 5+ letters, upper and lower case.<br>"
                    + "Password: 5+ chars with a letter, a digit and one of $ % *</small></center></html>", SwingConstants.CENTER);
            c.gridy = 5; p.add(rules, c);
            return p;
        }

        void buildGamePanel() {
            gamePanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
            JPanel grid = new JPanel(new GridLayout(MAX_GUESSES, WORD_LENGTH, 5, 5));
            for (int r = 0; r < MAX_GUESSES; r++) {
                for (int col = 0; col < WORD_LENGTH; col++) {
                    JLabel l = new JLabel(" ", SwingConstants.CENTER);
                    l.setOpaque(true);
                    l.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 28));
                    l.setPreferredSize(new Dimension(58, 58));
                    l.setBorder(BorderFactory.createLineBorder(Color.LIGHT_GRAY));
                    cells[r][col] = l;
                    grid.add(l);
                }
            }
            JPanel gridWrap = new JPanel(new FlowLayout(FlowLayout.CENTER));
            gridWrap.add(grid);

            JPanel north = new JPanel(new GridLayout(3, 1));
            north.add(info);
            north.add(pattern);
            north.add(hintsLabel);
            hintsLabel.setHorizontalAlignment(SwingConstants.CENTER);
            pattern.setFont(new Font(Font.MONOSPACED, Font.BOLD, 20));

            JButton play = new JButton("Play / Resume");
            JButton history = new JButton("History");
            play.addActionListener(e -> doPlay());
            history.addActionListener(e -> doHistory());
            guessBtn.addActionListener(e -> doGuess());
            guessField.addActionListener(e -> doGuess());
            hintBtn.addActionListener(e -> doHint());
            JPanel south = new JPanel(new FlowLayout(FlowLayout.CENTER, 6, 6));
            south.add(play);
            south.add(guessField);
            south.add(guessBtn);
            south.add(hintBtn);
            south.add(history);

            gamePanel.add(north, BorderLayout.NORTH);
            gamePanel.add(gridWrap, BorderLayout.CENTER);
            gamePanel.add(south, BorderLayout.SOUTH);
        }

        void buildAdminPanel() {
            adminPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
            JPanel daily = new JPanel(new FlowLayout(FlowLayout.LEFT));
            daily.setBorder(BorderFactory.createTitledBorder("Daily report (YYYY-MM-DD)"));
            JButton run = new JButton("Show");
            run.addActionListener(e -> doDailyReport());
            daily.add(dateField);
            daily.add(run);
            daily.add(dailyResult);

            JPanel user = new JPanel(new BorderLayout(6, 6));
            user.setBorder(BorderFactory.createTitledBorder("User report"));
            JPanel pick = new JPanel(new FlowLayout(FlowLayout.LEFT));
            JButton show = new JButton("Show");
            JButton reload = new JButton("Reload players");
            show.addActionListener(e -> doUserReport());
            reload.addActionListener(e -> loadPlayers());
            pick.add(playerBox);
            pick.add(show);
            pick.add(reload);
            userReportArea.setEditable(false);
            userReportArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
            user.add(pick, BorderLayout.NORTH);
            user.add(new JScrollPane(userReportArea), BorderLayout.CENTER);

            adminPanel.add(daily, BorderLayout.NORTH);
            adminPanel.add(user, BorderLayout.CENTER);
        }

        void error(String message) {
            JOptionPane.showMessageDialog(this, message, "Guess the Word", JOptionPane.WARNING_MESSAGE);
        }

        void doLogin() {
            try {
                user = Db.login(userField.getText().strip(), new String(passField.getPassword()));
                passField.setText("");
                enter();
            } catch (GameException e) {
                error(e.getMessage());
            }
        }

        void doRegister() {
            try {
                Db.register(userField.getText(), new String(passField.getPassword()), adminBox.isSelected());
                JOptionPane.showMessageDialog(this, "Account created. You can log in now.");
            } catch (GameException e) {
                error(e.getMessage());
            }
        }

        void enter() {
            session = null;
            who.setText("Signed in as " + user.username() + (user.staff() ? " (admin)" : ""));
            topBar.setVisible(true);
            if (user.staff()) {
                JTabbedPane tabs = new JTabbedPane();
                tabs.addTab("Play", gamePanel);
                tabs.addTab("Admin reports", adminPanel);
                main = tabs;
                loadPlayers();
            } else {
                main = gamePanel;
            }
            body.add(main, "main");
            render();
            cards.show(body, "main");
        }

        void logout() {
            body.remove(main);
            user = null;
            session = null;
            topBar.setVisible(false);
            cards.show(body, "auth");
        }

        void doPlay() {
            try {
                session = Db.startGame(user.id());
                guessField.setText("");
                render();
                guessField.requestFocusInWindow();
            } catch (GameException e) {
                error(e.getMessage());
            }
        }

        void doGuess() {
            if (session == null || session.over()) return;
            try {
                session = Db.submitGuess(user.id(), session.id, guessField.getText());
                guessField.setText("");
                render();
            } catch (GameException e) {
                error(e.getMessage());
            }
        }

        void doHint() {
            if (session == null || session.over()) return;
            try {
                HintResult h = Db.hint(user.id(), session.id);
                session = Db.getSession(session.id, user.id());
                render();
                info.setText("Hint: letter " + (h.position() + 1) + " is " + h.letter());
            } catch (GameException e) {
                error(e.getMessage());
            }
        }

        void doHistory() {
            try {
                StringBuilder sb = new StringBuilder();
                for (Session s : Db.history(user.id())) {
                    sb.append(s.playedOn).append("  ").append(String.format("%-11s", s.status.toUpperCase(Locale.ROOT)))
                            .append(s.guesses.size()).append('/').append(MAX_GUESSES).append("  ").append(s.category);
                    if (s.lost()) sb.append("  answer: ").append(s.answer);
                    sb.append("\n    guesses: ");
                    for (GuessRow g : s.guesses) sb.append(g.text()).append(' ');
                    sb.append("\n");
                }
                JTextArea area = new JTextArea(sb.length() == 0 ? "No games yet." : sb.toString(), 16, 46);
                area.setEditable(false);
                area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
                JOptionPane.showMessageDialog(this, new JScrollPane(area), "Game history", JOptionPane.PLAIN_MESSAGE);
            } catch (GameException e) {
                error(e.getMessage());
            }
        }

        void doDailyReport() {
            try {
                long[] r = Db.dailyReport(LocalDate.parse(dateField.getText().strip()));
                dailyResult.setText("Users who played: " + r[0] + "   Correct guesses: " + r[1]);
            } catch (java.time.format.DateTimeParseException e) {
                error("Invalid date. Use YYYY-MM-DD.");
            } catch (GameException e) {
                error(e.getMessage());
            }
        }

        void loadPlayers() {
            try {
                playerBox.removeAllItems();
                for (Player p : Db.players()) playerBox.addItem(p);
            } catch (GameException e) {
                error(e.getMessage());
            }
        }

        void doUserReport() {
            Player p = (Player) playerBox.getSelectedItem();
            if (p == null) return;
            try {
                List<String> lines = Db.userReport(p.id());
                userReportArea.setText(p.username() + "\n\n" + (lines.isEmpty() ? "No games played." : String.join("\n", lines)));
            } catch (GameException e) {
                error(e.getMessage());
            }
        }

        void render() {
            for (int r = 0; r < MAX_GUESSES; r++) {
                for (int c = 0; c < WORD_LENGTH; c++) {
                    JLabel cell = cells[r][c];
                    cell.setText(" ");
                    cell.setBackground(Color.WHITE);
                    cell.setForeground(Color.BLACK);
                    if (session != null && r < session.guesses.size()) {
                        GuessRow g = session.guesses.get(r);
                        cell.setText(String.valueOf(g.text().charAt(c)));
                        cell.setForeground(Color.WHITE);
                        String f = g.feedback().get(c);
                        cell.setBackground(f.equals("green") ? GREEN : f.equals("orange") ? ORANGE : GREY);
                    }
                }
            }
            boolean playing = session != null && !session.over();
            guessBtn.setEnabled(playing);
            hintBtn.setEnabled(playing);
            guessField.setEnabled(playing);

            if (session == null) {
                info.setText("Press \"Play / Resume\" to start a game.");
                pattern.setText(" ");
                hintsLabel.setText(" ");
                return;
            }
            if (session.won()) info.setText("You won! (" + session.category + ")");
            else if (session.lost()) info.setText("Out of guesses. The word was " + session.answer);
            else info.setText("Category: " + session.category + "   -   guess " + (session.guesses.size() + 1) + " of " + MAX_GUESSES);
            hintsLabel.setText("Hints left today: " + session.hintsRemaining);

            // letters already known (green guesses + hints), e.g. "_ A _ _ E"
            StringBuilder known = new StringBuilder();
            for (int i = 0; i < WORD_LENGTH; i++) {
                boolean shown = session.hints.contains(i);
                for (GuessRow g : session.guesses) if (g.feedback().get(i).equals("green")) shown = true;
                known.append(shown || session.lost() ? session.answer.charAt(i) : '_').append(' ');
            }
            pattern.setText(known.toString().trim());
        }
    }

    // =====================================================================================
    public static void main(String[] args) {
        try {
            Db.connect();
        } catch (SQLException e) {
            JOptionPane.showMessageDialog(null,
                    "Could not connect to the database:\n" + e.getMessage()
                            + "\n\nCheck DB_NAME / DB_USER / DB_PASSWORD / DB_HOST / DB_PORT (env vars or .env file),\n"
                            + "and that the PostgreSQL JDBC driver jar is added to the project.",
                    "Guess the Word", JOptionPane.ERROR_MESSAGE);
            return;
        }
        SwingUtilities.invokeLater(() -> new App().setVisible(true));
    }
}