package org.openremote.model.http;

import org.openremote.model.util.TextUtil;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.core.*;
import java.net.URI;


public class RequestParams {

    @HeaderParam(HttpHeaders.AUTHORIZATION)
    public String authorization;

    @HeaderParam("X-Forwarded-Proto")
    public String forwardedProtoHeader;

    @HeaderParam("X-Forwarded-Host")
    public String forwardedHostHeader;

    @Context
    public UriInfo uriInfo;

    public String getBearerAuth() {
        if (authorization == null || !authorization.startsWith("Bearer ") || authorization.split(" ").length != 2)
            return null;
        return authorization.split(" ")[1];
    }

    /**
     * Handles reverse proxying and returns the request base URI
     */
    public UriBuilder getExternalRequestBaseUri() {
        URI uri = this.uriInfo.getRequestUri();
        String scheme = TextUtil.isNullOrEmpty(this.forwardedProtoHeader) ? uri.getScheme() : this.forwardedProtoHeader;
        int port = uri.getPort();
        String host = uri.getHost();

        if (this.forwardedHostHeader != null) {
            String[] hostAndPort = this.forwardedHostHeader.split(":");
            if (hostAndPort.length == 1) {
                host = hostAndPort[0];
            } else if (hostAndPort.length == 2) {
                host = hostAndPort[0];
                port = Integer.parseInt(hostAndPort[1]);
            }
        }

        return this.uriInfo.getBaseUriBuilder().scheme(scheme).host(host).port(port);
    }
}
