package dev.claudev.adapter.fepipeline;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/** Polls a URL until it returns 2xx or the timeout elapses. Built on {@code java.net.http} — no extra dependency needed. */
final class HealthCheckExecutor implements StepExecutor {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Override
    public void execute(Map<String, Object> params) throws StepExecutionException {
        String url = Params.requireString(params, "url");
        int timeoutSeconds = Params.optionalInt(params, "timeoutSeconds", 30);

        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new StepExecutionException("Invalid HealthCheck url: " + url, e);
        }

        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).GET().build();
        Instant deadline = Instant.now().plusSeconds(timeoutSeconds);
        int lastStatus = -1;
        String lastError = null;

        while (Instant.now().isBefore(deadline)) {
            try {
                HttpResponse<Void> response = CLIENT.send(request, HttpResponse.BodyHandlers.discarding());
                lastStatus = response.statusCode();
                if (lastStatus >= 200 && lastStatus < 300) {
                    return;
                }
            } catch (IOException e) {
                lastError = e.getMessage();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new StepExecutionException("Interrupted while polling HealthCheck url: " + url, e);
            }

            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new StepExecutionException("Interrupted while polling HealthCheck url: " + url, e);
            }
        }

        throw new StepExecutionException(
                "HealthCheck timed out after " + timeoutSeconds + "s polling " + url
                        + " (last status=" + lastStatus + ", last error=" + lastError + ")");
    }
}
