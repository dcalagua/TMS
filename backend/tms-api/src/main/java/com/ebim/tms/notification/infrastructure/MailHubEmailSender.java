package com.ebim.tms.notification.infrastructure;

import com.ebim.tms.shared.reference.TemplateEmailPort;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link TemplateEmailPort} over EBIM MailHub (ADR-031): one {@code POST} per email to the
 * {@code mailhub-send} function, with the template's key and variables. MailHub owns the template,
 * the sender address and the delivery; TMS owns which template and with what.
 *
 * <pre>
 * POST {tms.mailhub.url}
 * X-MailHub-Key: {tms.mailhub.token}
 * x-region: {tms.mailhub.region}
 * {"to": [...], "content": {"type": "template", "templateKey": "...", "variables": {...}},
 *  "referenceId": "...", "idempotencyKey": "..."}
 * </pre>
 */
@Component
@EnableConfigurationProperties(MailHubProperties.class)
public class MailHubEmailSender implements TemplateEmailPort {

    private static final Logger log = LoggerFactory.getLogger(MailHubEmailSender.class);

    private final MailHubProperties properties;
    private final ObjectMapper json;
    private final HttpClient http;

    public MailHubEmailSender(MailHubProperties properties, ObjectMapper json) {
        this.properties = properties;
        this.json = json;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public boolean isAvailable() {
        return properties.configured();
    }

    @Override
    public void send(TemplateEmail email) {
        if (!properties.configured()) {
            throw new IllegalStateException("MailHub is not configured: set tms.mailhub.url and tms.mailhub.token.");
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(properties.url()))
                .timeout(properties.timeout())
                .header("X-MailHub-Key", properties.token())
                .header("x-region", properties.region())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body(email))))
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException failure) {
            throw new IllegalStateException("MailHub could not be reached: " + failure.getClass().getSimpleName(),
                    failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while sending through MailHub.", interrupted);
        }
        if (response.statusCode() / 100 != 2) {
            // The body is MailHub's own error message; it never echoes the token.
            throw new IllegalStateException("MailHub answered HTTP " + response.statusCode() + " for template '"
                    + email.templateKey() + "': " + abbreviate(response.body()));
        }
        log.info("MailHub accepted template '{}' (reference {})", email.templateKey(), email.referenceId());
    }

    /** The request body, in MailHub's shape. */
    static Map<String, Object> body(TemplateEmail email) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("type", "template");
        content.put("templateKey", email.templateKey());
        content.put("variables", email.variables());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("to", email.to());
        body.put("content", content);
        body.put("referenceId", email.referenceId());
        body.put("idempotencyKey", email.idempotencyKey());
        return body;
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 300 ? text : text.substring(0, 300) + "...";
    }
}
