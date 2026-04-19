import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Main {
    private static final List<Account> ACCOUNTS = Collections.synchronizedList(new ArrayList<>());

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8002;

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", Main::handleRoot);
        server.createContext("/docs", Main::handleDocs);
        server.createContext("/openapi.json", Main::handleOpenApi);
        server.createContext("/static/", Main::handleStatic);
        server.createContext("/accounts", Main::handleAccounts);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();

        System.out.println("API Java disponible sur http://127.0.0.1:" + port);
        System.out.println("Swagger local : http://127.0.0.1:" + port + "/docs");
    }

    private static void handleRoot(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, "{\"detail\":\"Methode non autorisee.\"}");
            return;
        }

        sendJson(exchange, 200, """
            {
              "message": "Bienvenue sur l'API bancaire Java",
              "documentation": "/docs"
            }
            """.trim());
    }

    private static void handleDocs(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, "{\"detail\":\"Methode non autorisee.\"}");
            return;
        }

        String html = """
            <!DOCTYPE html>
            <html>
            <head>
              <meta charset="utf-8">
              <link rel="stylesheet" href="/static/swagger-ui/swagger-ui.css">
              <link rel="shortcut icon" href="/static/swagger-ui/favicon.svg">
              <title>API Java - Swagger UI</title>
            </head>
            <body>
              <div id="swagger-ui"></div>
              <script src="/static/swagger-ui/swagger-ui-bundle.js"></script>
              <script>
                SwaggerUIBundle({
                  url: '/openapi.json',
                  dom_id: '#swagger-ui',
                  deepLinking: true,
                  docExpansion: 'list',
                  displayRequestDuration: true,
                  filter: true,
                  presets: [SwaggerUIBundle.presets.apis, SwaggerUIBundle.SwaggerUIStandalonePreset]
                });
              </script>
            </body>
            </html>
            """;

        sendResponse(exchange, 200, html, "text/html; charset=utf-8");
    }

    private static void handleOpenApi(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, "{\"detail\":\"Methode non autorisee.\"}");
            return;
        }

        String content = Files.readString(Path.of("openapi.json"), StandardCharsets.UTF_8);
        sendResponse(exchange, 200, content, "application/json; charset=utf-8");
    }

    private static void handleStatic(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, "{\"detail\":\"Methode non autorisee.\"}");
            return;
        }

        String path = exchange.getRequestURI().getPath();
        String relativePath = path.replaceFirst("^/static/", "");
        Path filePath = Path.of("static").resolve(relativePath).normalize();

        if (!Files.exists(filePath) || !filePath.startsWith(Path.of("static"))) {
            sendJson(exchange, 404, "{\"detail\":\"Ressource statique introuvable.\"}");
            return;
        }

        String contentType = detectContentType(filePath);
        byte[] bytes = Files.readAllBytes(filePath);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static void handleAccounts(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();

        if ("/accounts".equals(path) || "/accounts/".equals(path)) {
            if ("GET".equalsIgnoreCase(method)) {
                sendJson(exchange, 200, listAccountsJson());
                return;
            }
            if ("POST".equalsIgnoreCase(method)) {
                createAccount(exchange);
                return;
            }
            sendJson(exchange, 405, "{\"detail\":\"Methode non autorisee.\"}");
            return;
        }

        String remaining = path.substring("/accounts/".length());
        String[] parts = remaining.split("/");

        if (parts.length == 1 && "GET".equalsIgnoreCase(method)) {
            Account account = findAccount(parts[0]);
            if (account == null) {
                sendJson(exchange, 404, "{\"detail\":\"Compte introuvable.\"}");
                return;
            }
            sendJson(exchange, 200, accountDetailsJson(account));
            return;
        }

        if (parts.length == 2) {
            Account account = findAccount(parts[0]);
            if (account == null) {
                sendJson(exchange, 404, "{\"detail\":\"Compte introuvable.\"}");
                return;
            }

            if ("transactions".equals(parts[1]) && "GET".equalsIgnoreCase(method)) {
                sendJson(exchange, 200, transactionsJson(account.transactions));
                return;
            }

            if ("deposit".equals(parts[1]) && "POST".equalsIgnoreCase(method)) {
                applyTransaction(exchange, account, "deposit");
                return;
            }

            if ("withdraw".equals(parts[1]) && "POST".equalsIgnoreCase(method)) {
                applyTransaction(exchange, account, "withdraw");
                return;
            }
        }

        sendJson(exchange, 404, "{\"detail\":\"Route introuvable.\"}");
    }

    private static void createAccount(HttpExchange exchange) throws IOException {
        String body = readBody(exchange);
        String fullName = extractString(body, "full_name");
        String phoneNumber = extractString(body, "phone_number");
        String email = extractOptionalString(body, "email");
        Double initialBalance = extractDouble(body, "initial_balance");

        if (fullName == null || phoneNumber == null) {
            sendJson(exchange, 400, "{\"detail\":\"full_name et phone_number sont obligatoires.\"}");
            return;
        }

        double balance = initialBalance == null ? 0 : round(initialBalance);
        if (balance < 0) {
            sendJson(exchange, 400, "{\"detail\":\"Le solde initial doit etre positif ou nul.\"}");
            return;
        }

        Account account = new Account();
        account.id = UUID.randomUUID().toString();
        account.accountNumber = generateAccountNumber();
        account.fullName = fullName;
        account.phoneNumber = phoneNumber;
        account.email = email;
        account.balance = balance;
        account.createdAt = Instant.now().toString();

        if (balance > 0) {
            account.transactions.add(buildTransaction("deposit", balance, "Solde initial", balance));
        }

        ACCOUNTS.add(account);
        sendJson(exchange, 201, accountSummaryJson(account));
    }

    private static void applyTransaction(HttpExchange exchange, Account account, String transactionType) throws IOException {
        String body = readBody(exchange);
        Double amount = extractDouble(body, "amount");
        String description = extractOptionalString(body, "description");

        if (amount == null || amount <= 0) {
            sendJson(exchange, 400, "{\"detail\":\"Le montant doit etre strictement positif.\"}");
            return;
        }

        double roundedAmount = round(amount);
        if ("withdraw".equals(transactionType) && roundedAmount > account.balance) {
            sendJson(exchange, 400, "{\"detail\":\"Solde insuffisant pour effectuer ce retrait.\"}");
            return;
        }

        double newBalance = "deposit".equals(transactionType)
            ? round(account.balance + roundedAmount)
            : round(account.balance - roundedAmount);

        account.balance = newBalance;
        account.transactions.add(buildTransaction(transactionType, roundedAmount, description, newBalance));
        sendJson(exchange, 200, accountDetailsJson(account));
    }

    private static Transaction buildTransaction(String type, double amount, String description, double balanceAfter) {
        Transaction transaction = new Transaction();
        transaction.transactionId = UUID.randomUUID().toString();
        transaction.transactionType = type;
        transaction.amount = round(amount);
        transaction.description = description;
        transaction.balanceAfter = round(balanceAfter);
        transaction.createdAt = Instant.now().toString();
        return transaction;
    }

    private static Account findAccount(String accountId) {
        synchronized (ACCOUNTS) {
            for (Account account : ACCOUNTS) {
                if (account.id.equals(accountId)) {
                    return account;
                }
            }
        }
        return null;
    }

    private static String listAccountsJson() {
        StringBuilder json = new StringBuilder("[");
        synchronized (ACCOUNTS) {
            for (int i = 0; i < ACCOUNTS.size(); i++) {
                if (i > 0) {
                    json.append(',');
                }
                json.append(accountSummaryJson(ACCOUNTS.get(i)));
            }
        }
        json.append(']');
        return json.toString();
    }

    private static String accountSummaryJson(Account account) {
        return "{"
            + "\"id\":\"" + escape(account.id) + "\","
            + "\"account_number\":\"" + escape(account.accountNumber) + "\","
            + "\"full_name\":\"" + escape(account.fullName) + "\","
            + "\"phone_number\":\"" + escape(account.phoneNumber) + "\","
            + "\"email\":" + jsonStringOrNull(account.email) + ","
            + "\"balance\":" + formatDouble(account.balance) + ","
            + "\"created_at\":\"" + escape(account.createdAt) + "\""
            + "}";
    }

    private static String accountDetailsJson(Account account) {
        return "{"
            + "\"id\":\"" + escape(account.id) + "\","
            + "\"account_number\":\"" + escape(account.accountNumber) + "\","
            + "\"full_name\":\"" + escape(account.fullName) + "\","
            + "\"phone_number\":\"" + escape(account.phoneNumber) + "\","
            + "\"email\":" + jsonStringOrNull(account.email) + ","
            + "\"balance\":" + formatDouble(account.balance) + ","
            + "\"created_at\":\"" + escape(account.createdAt) + "\","
            + "\"transactions\":" + transactionsJson(account.transactions)
            + "}";
    }

    private static String transactionsJson(List<Transaction> transactions) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < transactions.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            Transaction tx = transactions.get(i);
            json.append("{")
                .append("\"transaction_id\":\"").append(escape(tx.transactionId)).append("\",")
                .append("\"transaction_type\":\"").append(escape(tx.transactionType)).append("\",")
                .append("\"amount\":").append(formatDouble(tx.amount)).append(",")
                .append("\"description\":").append(jsonStringOrNull(tx.description)).append(",")
                .append("\"balance_after\":").append(formatDouble(tx.balanceAfter)).append(",")
                .append("\"created_at\":\"").append(escape(tx.createdAt)).append("\"")
                .append("}");
        }
        json.append(']');
        return json.toString();
    }

    private static String generateAccountNumber() {
        String datePart = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        String randomPart = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        return "ACC-" + datePart + "-" + randomPart;
    }

    private static String detectContentType(Path filePath) {
        String filename = filePath.getFileName().toString();
        if (filename.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        if (filename.endsWith(".js")) {
            return "text/javascript; charset=utf-8";
        }
        if (filename.endsWith(".svg")) {
            return "image/svg+xml";
        }
        return "application/octet-stream";
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static String extractString(String body, String key) {
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"((?:\\\\.|[^\\\\\"])*)\"", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(body);
        if (!matcher.find()) {
            return null;
        }
        return unescape(matcher.group(1));
    }

    private static String extractOptionalString(String body, String key) {
        Pattern nullPattern = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*null");
        if (nullPattern.matcher(body).find()) {
            return null;
        }
        return extractString(body, key);
    }

    private static Double extractDouble(String body, String key) {
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)");
        Matcher matcher = pattern.matcher(body);
        if (!matcher.find()) {
            return null;
        }
        return Double.parseDouble(matcher.group(1));
    }

    private static String jsonStringOrNull(String value) {
        return value == null ? "null" : "\"" + escape(value) + "\"";
    }

    private static String escape(String value) {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r");
    }

    private static String unescape(String value) {
        return value
            .replace("\\\"", "\"")
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\\\", "\\");
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static String formatDouble(double value) {
        return Double.toString(round(value));
    }

    private static void sendJson(HttpExchange exchange, int statusCode, String body) throws IOException {
        sendResponse(exchange, statusCode, body, "application/json; charset=utf-8");
    }

    private static void sendResponse(HttpExchange exchange, int statusCode, String body, String contentType) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", contentType);
        headers.set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static class Account {
        String id;
        String accountNumber;
        String fullName;
        String phoneNumber;
        String email;
        double balance;
        String createdAt;
        List<Transaction> transactions = new ArrayList<>();
    }

    private static class Transaction {
        String transactionId;
        String transactionType;
        double amount;
        String description;
        double balanceAfter;
        String createdAt;
    }
}
