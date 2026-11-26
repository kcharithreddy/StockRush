package com.flashsale.order.assistant;

/** Why the assistant could not answer. Messages are safe to return to an admin: they never carry the key. */
public abstract sealed class AssistantException extends RuntimeException {

    AssistantException(String message) {
        super(message, null, false, false);
    }

    /** No API key configured. */
    public static final class Disabled extends AssistantException {
        Disabled() {
            super("ASSISTANT_DISABLED");
        }
    }

    /** Asked again before flashsale.assistant.min-interval passed. */
    public static final class TooSoon extends AssistantException {
        private final long retryAfterSeconds;

        TooSoon(long retryAfterSeconds) {
            super("TOO_SOON");
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public long retryAfterSeconds() {
            return retryAfterSeconds;
        }
    }

    /** The provider failed or refused; the message is its HTTP status and error code. */
    public static final class Upstream extends AssistantException {
        Upstream(String detail) {
            super(detail);
        }
    }
}
