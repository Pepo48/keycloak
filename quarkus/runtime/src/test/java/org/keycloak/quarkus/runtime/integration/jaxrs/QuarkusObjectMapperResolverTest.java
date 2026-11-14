package org.keycloak.quarkus.runtime.integration.jaxrs;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Stream;

import org.keycloak.authorization.authzen.AuthZen;
import org.keycloak.representations.AccessTokenResponse;
import org.keycloak.representations.idm.authorization.AuthorizationSchema;
import org.keycloak.representations.workflows.WorkflowRepresentation;
import org.keycloak.representations.workflows.WorkflowStepRepresentation;
import org.keycloak.services.util.ObjectMapperResolver;
import org.keycloak.ssf.transmitter.resources.AddSubjectRequest;

import org.junit.Test;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertThrows;

public class QuarkusObjectMapperResolverTest {

    @Test
    public void omitNullTokenResponseFields() throws Exception {
        var response = new AccessTokenResponse();
        response.setToken("access-token");
        var resolver = new QuarkusObjectMapperResolver();
        var mapper = resolver.getContext(AccessTokenResponse.class);

        var json = mapper.readTree(mapper.writeValueAsString(response));

        assertThat(json.get("access_token").asString(), equalTo("access-token"));
        assertThat(json.has("authorization_details"), is(false));
        assertThat(json.has("refresh_token"), is(false));

        var legacyMapper = new ObjectMapperResolver().getContext(Object.class);
        assertThat(legacyMapper.readTree(resolver.getContext(AccessTokenResponse.class).writeValueAsString(response)),
                equalTo(legacyMapper.readTree(legacyMapper.writeValueAsString(response))));
    }

    @Test
    public void serializeStreamsAsArrays() {
        var mapper = new QuarkusObjectMapperResolver().getContext(Stream.class);

        assertThat(mapper.writeValueAsString(Stream.of("first", "second")), equalTo("[\"first\",\"second\"]"));
    }

    @Test
    public void omitNullMapValues() {
        var values = new LinkedHashMap<String, Object>();
        values.put("visible", "value");
        values.put("hidden", null);
        var mapper = new QuarkusObjectMapperResolver().getContext(values.getClass());

        assertThat(mapper.writeValueAsString(values), equalTo("{\"visible\":\"value\"}"));
    }

    @Test
    public void honorPrettyPrinting() {
        String previous = System.getProperty("keycloak.jsonPrettyPrint");
        try {
            System.setProperty("keycloak.jsonPrettyPrint", "true");
            var mapper = new QuarkusObjectMapperResolver().getContext(Object.class);
            var values = new LinkedHashMap<String, String>();
            values.put("key", "value");

            assertThat(mapper.writeValueAsString(values), containsString("\n"));
        } finally {
            if (previous == null) {
                System.clearProperty("keycloak.jsonPrettyPrint");
            } else {
                System.setProperty("keycloak.jsonPrettyPrint", previous);
            }
        }
    }

    @Test
    public void preserveWorkflowDeserialization() throws Exception {
        String json = "{\"name\":\"test\",\"on\":\"user-created\",\"steps\":[{\"uses\":\"set-user-attribute\",\"custom\":\"value\"}]}";
        var resolver = new QuarkusObjectMapperResolver();
        var legacyMapper = new ObjectMapperResolver().getContext(Object.class);
        var expected = legacyMapper.readValue(json, WorkflowRepresentation.class);
        var actual = resolver.getContext(WorkflowRepresentation.class).readValue(json, WorkflowRepresentation.class);

        assertThat(legacyMapper.readTree(legacyMapper.writeValueAsString(actual)),
                equalTo(legacyMapper.readTree(legacyMapper.writeValueAsString(expected))));
    }

    @Test
    public void preserveAuthorizationSchemaForms() throws Exception {
        var resolver = new QuarkusObjectMapperResolver();
        String resource = "{\"type\":\"document\",\"scopes\":[\"read\",\"write\"]}";
        for (String types : List.of("[" + resource + "]", "{\"document\":" + resource + "}")) {
            String json = "{\"resourceTypes\":" + types + "}";
            var expected = new ObjectMapperResolver().getContext(Object.class).readValue(json, AuthorizationSchema.class);
            var actual = resolver.getContext(AuthorizationSchema.class).readValue(json, AuthorizationSchema.class);
            assertThat(actual.getResourceTypes().keySet(), equalTo(expected.getResourceTypes().keySet()));
            assertThat(actual.getResourceTypes().get("document").getScopes(),
                    equalTo(expected.getResourceTypes().get("document").getScopes()));
        }
    }

    @Test
    public void preserveWorkflowConfiguration() throws Exception {
        var resolver = new QuarkusObjectMapperResolver();
        var legacyMapper = new ObjectMapperResolver().getContext(Object.class);
        var workflow = WorkflowRepresentation.withName("test")
                .onEvent("user-created")
                .withSteps(WorkflowStepRepresentation.create().of("set-user-attribute")
                        .withConfig("key", "value").withConfig("values", "first", "second").build())
                .build();
        String json = legacyMapper.writeValueAsString(workflow);
        var actual = resolver.getContext(WorkflowRepresentation.class).readValue(json, WorkflowRepresentation.class);

        assertThat(legacyMapper.readTree(legacyMapper.writeValueAsString(actual)), equalTo(legacyMapper.readTree(json)));
        assertThat(legacyMapper.readTree(resolver.getContext(WorkflowRepresentation.class).writeValueAsString(workflow)),
                equalTo(legacyMapper.readTree(json)));
    }

    @Test
    public void preserveAuthZenOptions() throws Exception {
        var resolver = new QuarkusObjectMapperResolver();
        var legacyMapper = new ObjectMapperResolver().getContext(Object.class);
        for (var semantic : AuthZen.EvaluationsSemantic.values()) {
            var options = new AuthZen.Options(semantic);
            String json = legacyMapper.writeValueAsString(options);
            assertThat(resolver.getContext(AuthZen.Options.class).readValue(json, AuthZen.Options.class), equalTo(options));
            assertThat(legacyMapper.readTree(resolver.getContext(AuthZen.Options.class).writeValueAsString(options)),
                    equalTo(legacyMapper.readTree(json)));
        }
    }

    @Test
    public void preserveSubjectIdentifierFormatsAndExtensions() throws Exception {
        var resolver = new QuarkusObjectMapperResolver();
        var legacy = new ObjectMapperResolver().getContext(Object.class);
        var mapper = resolver.getContext(AddSubjectRequest.class);
        for (String subject : List.of("null",
                "{\"format\":\"iss_sub\",\"iss\":\"https://issuer.example\",\"sub\":\"user\"}",
                "{\"subject_type\":\"iss-sub\",\"iss\":\"https://issuer.example\",\"sub\":\"user\"}",
                "{\"format\":\"email\",\"email\":\"user@example.com\"}",
                "{\"format\":\"complex\",\"user\":{\"format\":\"email\",\"email\":\"user@example.com\"}}",
                "{\"format\":\"complex\",\"user\":{\"format\":\"iss_sub\",\"iss\":\"https://issuer.example\",\"sub\":\"user\"}}",
                "{\"format\":\"custom-format\",\"extension\":{\"values\":[1,true,null]}}")) {
            String json = "{\"subject\":" + subject + "}";
            var expected = legacy.readValue(json, AddSubjectRequest.class);
            var actual = mapper.readValue(json, AddSubjectRequest.class);
            assertThat(subject, legacy.valueToTree(actual), equalTo(legacy.valueToTree(expected)));
            assertThat(subject, legacy.readTree(mapper.writeValueAsString(actual)), equalTo(legacy.valueToTree(expected)));
        }
    }

    @Test
    public void rejectInvalidSubjectIdentifierFormats() {
        var resolver = new QuarkusObjectMapperResolver();
        for (String subject : List.of("{}", "{\"format\":null}", "{\"format\":42}")) {
            String json = "{\"subject\":" + subject + "}";
            assertThrows(com.fasterxml.jackson.core.JsonProcessingException.class,
                    () -> new ObjectMapperResolver().getContext(Object.class).readValue(json, AddSubjectRequest.class));
            assertThrows(tools.jackson.databind.exc.MismatchedInputException.class,
                    () -> resolver.getContext(AddSubjectRequest.class).readValue(json, AddSubjectRequest.class));
        }
    }

}
