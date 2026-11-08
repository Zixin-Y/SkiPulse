package config;

public final class ConsumerConfig {

    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private final String virtualHost;
    private final int consumerThreads;
    private final int prefetch;
    private final int topN;

    private ConsumerConfig(
            String host,
            int port,
            String username,
            String password,
            String virtualHost,
            int consumerThreads,
            int prefetch,
            int topN) {

        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
        this.virtualHost = virtualHost;
        this.consumerThreads = consumerThreads;
        this.prefetch = prefetch;
        this.topN = topN;
    }

    public static ConsumerConfig fromEnvironment() {
        // Read connection settings and consumer tuning parameters.
        return new ConsumerConfig(
                requiredEnv("RABBITMQ_HOST"),
                intEnv("RABBITMQ_PORT", 5672, 1, 65535),
                requiredEnv("RABBITMQ_USERNAME"),
                requiredEnv("RABBITMQ_PASSWORD"),
                optionalEnv("RABBITMQ_VHOST", "/"),
                intEnv("CONSUMER_THREADS", 10, 1, 80),
                intEnv("CONSUMER_PREFETCH", 10, 1, 1000),
                intEnv("LEADERBOARD_TOP_N", 10, 1, 1000));
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);

        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "Missing required environment variable: " + name);
        }

        // Preserve the exact value, including password characters.
        return value;
    }

    private static String optionalEnv(
            String name, String defaultValue) {

        String value = System.getenv(name);

        if (value == null || value.isBlank()) {
            return defaultValue;
        }

        return value;
    }

    private static int intEnv(
            String name,
            int defaultValue,
            int minimum,
            int maximum) {

        String value = System.getenv(name);

        if (value == null || value.isBlank()) {
            return defaultValue;
        }

        final int parsed;

        try {
            parsed = Integer.parseInt(value.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    name + " must be an integer", exception);
        }

        if (parsed < minimum || parsed > maximum) {
            throw new IllegalArgumentException(
                    name + " must be between "
                            + minimum + " and " + maximum);
        }

        return parsed;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    public String getVirtualHost() {
        return virtualHost;
    }

    public int getConsumerThreads() {
        return consumerThreads;
    }

    public int getPrefetch() {
        return prefetch;
    }

    public int getTopN() {
        return topN;
    }
}