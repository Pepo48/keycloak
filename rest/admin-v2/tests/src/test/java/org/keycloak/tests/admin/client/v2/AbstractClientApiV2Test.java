package org.keycloak.tests.admin.client.v2;

import jakarta.ws.rs.core.HttpHeaders;

import org.keycloak.admin.api.client.ClientApi;
import org.keycloak.admin.api.client.ClientsApi;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.wrapper.Clients;
import org.keycloak.services.client.ClientServiceHelper;
import org.keycloak.testframework.annotations.InjectAdminClient;
import org.keycloak.testframework.remote.runonserver.InjectRunOnServer;
import org.keycloak.testframework.remote.runonserver.RunOnServerClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.HttpMessage;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;

public abstract class AbstractClientApiV2Test {
    protected static ObjectMapper mapper;

    @InjectAdminClient
    protected Keycloak adminClient;

    public String getRealmName() {
        return "master";
    }

    public String getClientsApiUrl() {
        return "http://localhost:8080/admin/api/%s/clients/v2".formatted(getRealmName());
    }
    public String getClientApiUrl(String clientId) {
        return getClientApiUrl(getRealmName(), clientId);
    }

    public String getClientApiUrl(String realmName, String clientId) {
        return "http://localhost:8080/admin/api/%s/clients/v2/%s".formatted(realmName, clientId);
    }

    public ClientsApi getClientsApi() {
        return adminClient.clients(getRealmName(), Clients.class).v2();
    }

    public ClientsApi getClientsApi(String realmName) {
        return getClientsApi(adminClient, realmName);
    }

    public ClientsApi getClientsApi(Keycloak adminClient) {
        return getClientsApi(adminClient, getRealmName());
    }

    public ClientsApi getClientsApi(Keycloak adminClient, String realmName) {
        return adminClient.clients(realmName, Clients.class).v2();
    }

    public ClientApi getClientApi(String clientId) {
        return getClientApi(getRealmName(), clientId);
    }

    public ClientApi getClientApi(String realmName, String clientId) {
        return getClientApi(adminClient, realmName, clientId);
    }

    public ClientApi getClientApi(Keycloak adminClient, String realmName, String clientId) {
        return adminClient.clients(realmName, Clients.class).v2().client(clientId);
    }




    @BeforeAll
    public static void setupMapper() {
        mapper = new ObjectMapper();
        if (!ClientServiceHelper.isLegacyClientServiceEnabled()) {
            Logger.getLogger(AbstractClientApiV2Test.class).infof("New Client service is used");
        }
    }

    // BEGIN remove once we drop support for legacy ClientService that uses Client API v1 under hood
    @InjectRunOnServer
    RunOnServerClient runOnServer;

    @BeforeEach
    public void setupNewClientService() {
        var isLegacyEnabled = ClientServiceHelper.isLegacyClientServiceEnabled();
        if (!isLegacyEnabled) {
            runOnServer.run(session -> System.setProperty("kc.admin-v2.client-service.legacy.enabled", "false"));
        }
    }
    // END

    protected void setAuthHeader(HttpMessage request) {
        String token = adminClient.tokenManager().getAccessTokenString();
        request.setHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    // TODO Rewrite the tests to not need explicit auth. They should use the admin client directly.
    protected static void setAuthHeader(HttpMessage request, Keycloak adminClient) {
        String token = adminClient.tokenManager().getAccessTokenString();
        request.setHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }
}
