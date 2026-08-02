package com.chtholly.agent.web;

import java.io.IOException;

/**
 * Executes one HTTP request without applying redirect or content policy.
 */
@FunctionalInterface
public interface WebHttpTransport {

    /**
     * Executes one request.
     *
     * @param request transport request
     * @return streaming response
     * @throws IOException when the network exchange fails
     * @throws InterruptedException when the exchange is interrupted
     */
    WebTransportResponse execute(WebTransportRequest request) throws IOException, InterruptedException;
}
