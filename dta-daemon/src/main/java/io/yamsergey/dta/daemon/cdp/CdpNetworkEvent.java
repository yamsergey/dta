package io.yamsergey.dta.daemon.cdp;

import java.util.Map;

/**
 * Sealed interface for Chrome DevTools Protocol Network domain events.
 *
 * <p>These events are received when Network.enable is called on a CDP session.</p>
 */
public sealed interface CdpNetworkEvent {

    /**
     * Returns the request ID that all related events share.
     */
    String requestId();

    /**
     * Returns the event timestamp (milliseconds since epoch).
     */
    long timestamp();

    /**
     * Redirect response data included in RequestWillBeSent when a redirect occurred.
     *
     * @param url        the URL that was redirected from
     * @param status     HTTP status code (301, 302, 307, 308)
     * @param statusText HTTP status text
     * @param headers    response headers from the redirect
     */
    record RedirectResponse(
        String url,
        int status,
        String statusText,
        Map<String, String> headers
    ) {}

    /**
     * Fired when page is about to send HTTP request.
     *
     * @param requestId        unique request identifier
     * @param loaderId         loader identifier
     * @param documentURL      URL of the document this request is loaded for
     * @param url              request URL
     * @param method           HTTP method (GET, POST, etc.)
     * @param headers          request headers
     * @param postData         HTTP POST request body (may be null)
     * @param timestamp        event timestamp
     * @param type             resource type (Document, Stylesheet, Image, etc.)
     * @param redirectResponse if this request is due to a redirect, contains the redirect response
     */
    record RequestWillBeSent(
        String requestId,
        String loaderId,
        String documentURL,
        String url,
        String method,
        Map<String, String> headers,
        String postData,
        long timestamp,
        String type,
        RedirectResponse redirectResponse
    ) implements CdpNetworkEvent {}

    /**
     * Fired when HTTP response is available.
     *
     * @param requestId    unique request identifier
     * @param loaderId     loader identifier
     * @param url          response URL
     * @param status       HTTP response status code
     * @param statusText   HTTP response status text
     * @param headers      response headers
     * @param mimeType     resource mimeType
     * @param timestamp    event timestamp
     * @param protocol     protocol used (e.g., "http/1.1", "h2")
     * @param encodedDataLength actual bytes received
     */
    record ResponseReceived(
        String requestId,
        String loaderId,
        String url,
        int status,
        String statusText,
        Map<String, String> headers,
        String mimeType,
        long timestamp,
        String protocol,
        long encodedDataLength
    ) implements CdpNetworkEvent {}

    /**
     * Fired when HTTP request has finished loading.
     *
     * @param requestId         unique request identifier
     * @param timestamp         event timestamp
     * @param encodedDataLength total encoded bytes received
     */
    record LoadingFinished(
        String requestId,
        long timestamp,
        long encodedDataLength
    ) implements CdpNetworkEvent {}

    /**
     * Fired when HTTP request has failed to load.
     *
     * @param requestId   unique request identifier
     * @param timestamp   event timestamp
     * @param errorText   localized error message
     * @param canceled    true if loading was canceled
     * @param blockedReason reason why request was blocked (may be null)
     */
    record LoadingFailed(
        String requestId,
        long timestamp,
        String errorText,
        boolean canceled,
        String blockedReason
    ) implements CdpNetworkEvent {}

    /**
     * Fired when data chunk was received over the network.
     *
     * @param requestId      unique request identifier
     * @param timestamp      event timestamp
     * @param dataLength     data chunk length
     * @param encodedDataLength actual bytes received
     */
    record DataReceived(
        String requestId,
        long timestamp,
        long dataLength,
        long encodedDataLength
    ) implements CdpNetworkEvent {}
}
