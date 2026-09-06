package com.finagent.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;

/**
 * Central, typed configuration tree for FINAGENT.
 *
 * <p>Every value is overridable by environment variables (see .env.example). No {@code @Value}
 * scattering is allowed anywhere else in the codebase.</p>
 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "finagent")
public class FinAgentProperties {

    private final App app = new App();
    private final Market market = new Market();
    private final News news = new News();
    private final Risk risk = new Risk();
    private final Ai ai = new Ai();
    private final Research research = new Research();
    private final Sentiment sentiment = new Sentiment();
    private final Auth auth = new Auth();
    private final Swagger swagger = new Swagger();
    private final RateLimit rateLimit = new RateLimit();

    @Getter
    @Setter
    public static class App {
        /** Display name reported by the health endpoint. */
        private String name = "FinAgent";
        /** Application version reported by the health endpoint. */
        private String version = "1.0.0";
        /**
         * Browser origins allowed to call /api/** (Phase 11 dashboard).
         * Local Vite dev server by default; override via FINAGENT_CORS_ALLOWED_ORIGINS.
         */
        private List<String> corsAllowedOrigins = List.of("http://localhost:5173");
    }

    /** Market data provider settings (Phase 3). Credentials ONLY from environment variables. */
    @Getter
    @Setter
    public static class Market {
        /** stub | alpha-vantage */
        private String provider = "stub";
        private String baseUrl = "https://www.alphavantage.co";
        /** Alpha Vantage API key - injected via ALPHA_VANTAGE_API_KEY, never hardcoded. */
        private String apiKey = "";
        /** Connect/read timeout for provider HTTP calls. */
        private Duration timeout = Duration.ofSeconds(5);
        /** Retry attempts for transient failures (>= 1). */
        private int retryAttempts = 3;
        /** Base backoff between retries; grows linearly per attempt. */
        private Duration retryBackoff = Duration.ofMillis(500);
    }

    /** Financial news provider settings (Phase 4). Credentials ONLY from environment variables. */
    @Getter
    @Setter
    public static class News {
        /** stub | newsapi */
        private String provider = "stub";
        private String baseUrl = "https://newsapi.org";
        /** NewsAPI key - injected via NEWS_API_KEY, never hardcoded. */
        private String apiKey = "";
        /** Connect/read timeout for provider HTTP calls. */
        private Duration timeout = Duration.ofSeconds(5);
        /** Retry attempts for transient failures (>= 1). */
        private int retryAttempts = 2;
        /** Base backoff between retries; grows linearly per attempt. */
        private Duration retryBackoff = Duration.ofMillis(500);
    }

    /**
     * Deterministic risk engine settings (Phase 6). The risk-free rate is NOT
     * silently invented - if it is not configured, the Sharpe ratio is reported
     * as unavailable.
     */
    @Getter
    @Setter
    public static class Risk {
        /** Annual risk-free rate (e.g. 0.0425 == 4.25%); null = not configured. */
        private Double riskFreeRate;
        /** Annualization factor for daily data (252 trading days per year). */
        private int tradingDaysPerYear = 252;
        /** Minimum aligned observations required for beta estimation. */
        private int minObservations = 2;
        /** Benchmark used when the caller does not specify one (e.g. SPY). */
        private String defaultBenchmark = "SPY";
        /** stub (only option today; real benchmark adapter connects later). */
        private String benchmarkProvider = "stub";
    }

    /**
     * AI research agent settings (Phase 7). The model API key is NOT here — it is
     * supplied only via the {@code SPRING_AI_OPENAI_API_KEY} environment variable
     * (see {@code spring.ai.openai.api-key} in application.yml).
     */
    @Getter
    @Setter
    public static class Ai {
        /** Chat model id, e.g. gpt-4o-mini. */
        private String model = "gpt-4o-mini";
        /** Sampling temperature; low keeps interpretations factual and stable. */
        private double temperature = 0.2;
        /** Maximum completion tokens for one interpretation. */
        private int maxTokens = 800;
        /**
         * Phase 17: hard bound on one LLM synthesis call. A hung model call
         * fails the job (SYNTHESIS_FAILED) instead of occupying a research
         * thread forever.
         */
        private Duration synthesisTimeout = Duration.ofSeconds(60);
    }

    /**
     * Research orchestration settings (Phase 8): bounded async executor for
     * background runs. No broker — a local thread pool with a bounded queue.
     */
    @Getter
    @Setter
    public static class Research {
        /**
         * Master switch for the research workflow beans + REST endpoints.
         * The {@code dev} profile (no database) sets this to false.
         */
        private boolean enabled = true;
        /** Core threads for background research runs. */
        private int corePoolSize = 2;
        /** Max threads for background research runs. */
        private int maxPoolSize = 4;
        /** Queued runs before the rejection policy applies. */
        private int queueCapacity = 50;
    }

    /**
     * Phase 16: authentication boundary settings. Secrets ONLY from environment
     * variables. The dev/test dummy JWT secret is NOT safe for production —
     * the {@code prod} profile refuses to start with it (see
     * ProductionSecretValidator).
     */
    @Getter
    @Setter
    public static class Auth {
        /**
         * Master switch. {@code false} in the DB-less dev profile (no users can
         * exist there); the API is then open, exactly like before Phase 16.
         */
        private boolean enabled = true;
        /**
         * HS256 signing secret (min 32 bytes when decoded). Injected via
         * FINAGENT_AUTH_JWT_SECRET; the default below is a non-secret
         * development placeholder.
         */
        private String jwtSecret = "finagent-dev-only-jwt-secret-not-for-production-use-0123456789";
        /** Access-token lifetime (short-lived; renewed via refresh tokens). */
        private Duration tokenTtl = Duration.ofHours(1);
        /**
          * Task 2: refresh-token lifetime (long-lived session credential, rotated
          * on every use). Injected via FINAGENT_AUTH_REFRESH_TTL; the default below
          * applies to dev/test — production may override it via the environment.
          */
        private Duration refreshTokenTtl = Duration.ofDays(14);
        /** BCrypt cost factor (4-31; 10 is the production-appropriate default). */
        private int bcryptStrength = 10;
        /**
          * Task 3: {@code Secure} flag for the refresh-token cookie. {@code false}
          * by default so plain-HTTP local development (Vite proxy, H2 tests)
          * keeps working — browsers would silently drop a {@code Secure} cookie
          * over HTTP. FORCED to {@code true} in {@code application-prod.yml};
          * production must always serve HTTPS anyway (HSTS is emitted there).
          * Injected via FINAGENT_AUTH_COOKIE_SECURE.
          */
        private boolean cookieSecure = false;
        /**
          * Task 3: {@code SameSite} attribute for the refresh-token cookie
          * ({@code Lax} or {@code Strict}; {@code None} only for genuine
          * cross-site deployments, which additionally require
          * {@code cookieSecure=true} or browsers reject the cookie).
          * Default {@code Lax}: the SPA is same-origin in dev (Vite proxy) and
          * same-site in the default production layout (reverse proxy or
          * sibling origins), and the cookie-bearing endpoints are POST-only,
          * so Lax already blocks cross-site presentation (see docs/security.md
          * §8). Injected via FINAGENT_AUTH_COOKIE_SAME_SITE.
          */
        private String cookieSameSite = "Lax";
        /**
          * Task 4: refresh-token housekeeping policy (nested so the properties
          * read {@code finagent.auth.refresh-token.*}, e.g.
          * {@code finagent.auth.refresh-token.cleanup-enabled}).
          */
        private final RefreshTokenMaintenance refreshToken = new RefreshTokenMaintenance();
    }

    /**
      * Task 4: refresh-token housekeeping knobs. Retention is a grace period:
      * dead rows (expired, or revoked) are kept this long before the cleanup
      * job deletes them, preserving forensic/audit usefulness and guaranteeing
      * that no row which could still influence rotation or reuse detection is
      * ever removed (see {@code RefreshTokenCleanupService} for the proof).
      */
    @Getter
    @Setter
    public static class RefreshTokenMaintenance {
        /** Master switch for the scheduled cleanup job (checked every run). */
        private boolean cleanupEnabled = true;
        /** How often the cleanup job runs (fixed delay between runs). */
        private Duration cleanupInterval = Duration.ofHours(1);
        /** Grace period after expiry/revocation before a dead row is deleted. */
        private Duration retention = Duration.ofDays(7);
    }

    /**
     * Phase 16: OpenAPI/Swagger surface switch. Open in dev/test, closed in
     * production (see application-prod.yml).
     */
    @Getter
    @Setter
    public static class Swagger {
        private boolean enabled = true;
    }

    /**
     * Phase 16: in-memory fixed-window rate limits (per client IP + group).
     * Single-instance only — not a distributed limiter (documented in
     * docs/security.md).
     */
    @Getter
    @Setter
    public static class RateLimit {
        /** Sensitive auth attempts (register/login) per window, per IP. */
        private int authMaxAttempts = 10;
        /** Window for auth attempts. */
        private Duration authWindow = Duration.ofMinutes(1);
        /** Expensive research submissions per window, per IP. */
        private int researchMaxAttempts = 30;
        /** Window for research submissions. */
        private Duration researchWindow = Duration.ofMinutes(1);
    }
    /**
     * News sentiment settings (Phase 9): small, maintainable, overridable lexicon
     * plus the scoring thresholds. Deliberately keyword-based and transparent —
     * see {@code docs/sentiment-analysis.md} for methodology and limitations.
     */
    @Getter
    @Setter
    public static class Sentiment {
        /** Positive signal words (matched whole-word, case-insensitive). */
        private List<String> positiveTerms = List.of(
                "growth", "profit", "profits", "profitable", "upgrade", "upgrades", "upgraded",
                "beat", "beats", "strong", "stronger", "strength", "record", "records",
                "surge", "surges", "surging", "gain", "gains", "rally", "rallies",
                "outperform", "bullish", "breakthrough", "robust", "soar", "soars",
                "jump", "jumps", "rise", "rises", "rising");
        /** Negative signal words (matched whole-word, case-insensitive). */
        private List<String> negativeTerms = List.of(
                "loss", "losses", "downgrade", "downgrades", "downgraded", "decline",
                "declines", "declining", "weak", "weakness", "miss", "misses", "missed",
                "lawsuit", "lawsuits", "fall", "falls", "falling", "drop", "drops",
                "plunge", "plunges", "bearish", "warning", "warnings", "fraud",
                "bankruptcy", "layoff", "layoffs", "slump", "tumble", "crash");
        /** Aggregate/article score at or above this is POSITIVE. */
        private double positiveThreshold = 0.2;
        /** Aggregate/article score at or below this is NEGATIVE. */
        private double negativeThreshold = -0.2;
        /** Lexicon hits yielding full (1.0) confidence; fewer scale linearly. */
        private double confidenceHits = 5.0;
    }
}
