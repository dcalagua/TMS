package com.ebim.tms.iam.infrastructure;

import com.ebim.tms.iam.application.AuthEmailProperties;
import com.ebim.tms.iam.application.AuthLinkIssuer;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link AuthLinkIssuer} over the Supabase Auth admin API (ADR-031):
 * {@code POST /auth/v1/admin/generate_link}. Unlike {@code resetPasswordForEmail} or
 * {@code inviteUserByEmail}, it returns the link and sends nothing, which is what lets MailHub
 * deliver it in EBIM's own template.
 *
 * <ul>
 *   <li>{@code type: recovery} - the password reset, for an account that exists;</li>
 *   <li>{@code type: invite} - a new user: Auth creates the account and returns its link. When the
 *       account already exists Auth refuses the invite ({@code 422}), and a recovery link to that same
 *       account is issued instead - it lands on the same page and lets the person set a password.</li>
 * </ul>
 *
 * <p>Authenticated with the project's {@code service_role} key, in both headers - the gateway reads
 * {@code apikey}, Auth reads the bearer - the same way the evidence store does (ADR-020). The key
 * is never logged and never leaves the backend.
 */
@Component
public class SupabaseAuthLinkIssuer implements AuthLinkIssuer {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final AuthEmailProperties properties;
    private final ObjectMapper json;
    private final HttpClient http;

    public SupabaseAuthLinkIssuer(AuthEmailProperties properties, ObjectMapper json) {
        this.properties = properties;
        this.json = json;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public boolean isConfigured() {
        return !properties.supabase().url().isEmpty() && !properties.supabase().serviceKey().isEmpty();
    }

    @Override
    public Optional<String> recoveryLink(String email, String redirectTo) {
        HttpResponse<String> response = generate("recovery", email, redirectTo);
        // No Auth account for the address: an ordinary outcome (a TMS profile not linked yet), not a fault.
        if (response.statusCode() == 404 || response.statusCode() == 422) {
            return Optional.empty();
        }
        requireSuccess(response);
        return Optional.ofNullable(actionLink(json.readTree(response.body())));
    }

    @Override
    public Optional<IssuedLink> invitationLink(String email, String redirectTo) {
        HttpResponse<String> response = generate("invite", email, redirectTo);
        if (response.statusCode() == 422) {
            // The account exists already: invited before and never finished, or made by hand.
            response = generate("recovery", email, redirectTo);
            if (response.statusCode() == 404 || response.statusCode() == 422) {
                return Optional.empty();
            }
        }
        requireSuccess(response);
        JsonNode answer = json.readTree(response.body());
        String link = actionLink(answer);
        UUID account = userId(answer);
        return link == null || account == null ? Optional.empty() : Optional.of(new IssuedLink(link, account));
    }

    private HttpResponse<String> generate(String type, String email, String redirectTo) {
        String base = properties.supabase().url().replaceAll("/+$", "");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", type);
        body.put("email", email);
        body.put("redirect_to", redirectTo);
        String key = properties.supabase().serviceKey();
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/auth/v1/admin/generate_link"))
                .timeout(TIMEOUT)
                .header("apikey", key)
                .header("Authorization", "Bearer " + key)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build();
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException failure) {
            throw new IllegalStateException("Supabase Auth could not be reached: " + failure.getClass().getSimpleName(),
                    failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while asking Supabase Auth for a link.", interrupted);
        }
    }

    private static void requireSuccess(HttpResponse<String> response) {
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("Supabase Auth answered HTTP " + response.statusCode()
                    + " to generate_link.");
        }
    }

    /**
     * {@code action_link} sits at the top level of GoTrue's answer; some versions nest it under
     * {@code properties}, as the JavaScript client presents it. Either is read.
     */
    static String actionLink(JsonNode answer) {
        for (JsonNode node : new JsonNode[] {answer.path("action_link"), answer.path("properties").path("action_link")}) {
            if (node.isString() && !node.asString().isBlank()) {
                return node.asString();
            }
        }
        return null;
    }

    /** The account's id: the user is the top level of GoTrue's answer, or under {@code user}. */
    static UUID userId(JsonNode answer) {
        for (JsonNode node : new JsonNode[] {answer.path("id"), answer.path("user").path("id")}) {
            if (node.isString()) {
                try {
                    return UUID.fromString(node.asString());
                } catch (IllegalArgumentException notAUuid) {
                    // try the next shape
                }
            }
        }
        return null;
    }
}
