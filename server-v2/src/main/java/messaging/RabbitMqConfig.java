package messaging;

public final class RabbitMqConfig {

    // Connection settings and publisher channel pool size
    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private final String virtualHost;
    private final int channelPoolSize;

    // Create a configuration object with all required settings
    private RabbitMqConfig(
            String host,
            int port,
            String username,
            String password,
            String virtualHost,
            int channelPoolSize) {

        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
        this.virtualHost = virtualHost;
        this.channelPoolSize = channelPoolSize;
    }

    // Load settings from the process environment
    public static RabbitMqConfig fromEnvironment() {
        String host = requiredEnv("RABBITMQ_HOST");
        int port = positiveIntEnv("RABBITMQ_PORT", 5672);

        if (port > 65535) {
            throw new IllegalArgumentException(
                    "RABBITMQ_PORT must be between 1 and 65535"
            );
        }

        String username = requiredEnv("RABBITMQ_USERNAME");
        String password = requiredEnv("RABBITMQ_PASSWORD");
        String virtualHost = envOrDefault("RABBITMQ_VHOST", "/");
        int poolSize = positiveIntEnv("RABBITMQ_CHANNEL_POOL_SIZE", 16);

        return new RabbitMqConfig(
                host, port, username, password, virtualHost, poolSize
        );
    }

    // Require a nonblank value without logging its contents
    private static String requiredEnv(String name) {
        String value = System.getenv(name);

        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "Missing required environment variable: " + name
            );
        }

        return value;
    }

    // Use the fallback only when the variable is not defined
    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null ? fallback : value;
    }

    // Parse a positive integer and report configuration errors clearly
    private static int positiveIntEnv(String name, int fallback) {
        String value = envOrDefault(name, Integer.toString(fallback));

        try {
            int parsed = Integer.parseInt(value);

            if (parsed > 0) {
                return parsed;
            }
        } catch (NumberFormatException ignored) {
            // Report the same error for malformed and out-of-range integers.
        }

        throw new IllegalArgumentException(
                name + " must be a positive integer"
        );
    }

    // Provide read access without allowing settings to be changed
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

    public int getChannelPoolSize() {
        return channelPoolSize;
    }
}