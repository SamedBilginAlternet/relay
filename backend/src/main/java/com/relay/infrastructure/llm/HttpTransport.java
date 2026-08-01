package com.relay.infrastructure.llm;

/** The one seam that lets the key-rotation logic be tested without a network. */
public interface HttpTransport {

    Reply post(String url, String apiKey, String jsonBody);

    /**
     * @param retryAfter what the provider said on 429 — {@code null} when it said nothing.
     *                   Parking a key for a flat minute when Groq asked for seven seconds
     *                   keeps a single-key install in fallback far longer than it has to be.
     */
    record Reply(int status, String body, java.time.Duration retryAfter) {

        public Reply(int status, String body) {
            this(status, body, null);
        }

        public boolean ok() {
            return status >= 200 && status < 300;
        }

        /** 429 or a quota/billing rejection — rotate to the next key. */
        public boolean shouldRotate() {
            if (status == 429 || status == 401 || status == 403 || status == 402) {
                return true;
            }
            if (status >= 500) {
                return true;
            }
            String lower = body == null ? "" : body.toLowerCase();
            return lower.contains("rate limit") || lower.contains("quota") || lower.contains("insufficient");
        }

        /**
         * The provider rejected the key itself rather than throttling it — revoked or
         * unauthorised. Waiting does not help; only a new key does, so the pool retires it.
         *
         * <p>402 is deliberately <em>not</em> here. It means "no balance", and a top-up fixes
         * it from the other side without anyone touching this process — a retired key would
         * stay dead until the next deploy, so somebody would pay and see no change. It is
         * parked like a rate limit instead, and comes back on its own.
         */
        public boolean refused() {
            return status == 401 || status == 403;
        }

        /** No credit left. Fixable by the account owner, so it waits rather than dies. */
        public boolean outOfCredit() {
            return status == 402;
        }
    }
}
