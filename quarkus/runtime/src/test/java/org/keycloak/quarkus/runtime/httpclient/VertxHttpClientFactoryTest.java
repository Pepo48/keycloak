/*
 * Copyright 2026 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.keycloak.quarkus.runtime.httpclient;

import org.keycloak.quarkus.runtime.configuration.AbstractConfigurationTest;

import io.vertx.core.http.PoolOptions;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class VertxHttpClientFactoryTest extends AbstractConfigurationTest {

    @Test
    public void preservesDefaultPoolSize() {
        initConfig();
        VertxHttpClientFactory factory = new VertxHttpClientFactory();
        factory.init(null);
        PoolOptions options = factory.buildPoolOptions();
        assertEquals(64, options.getHttp1MaxSize());
        assertEquals(new PoolOptions().getHttp2MaxSize(), options.getHttp2MaxSize());
    }

    @Test
    public void preservesConfiguredPoolSize() {
        putEnvVar("KC_SPI_CONNECTIONS_HTTP_CLIENT__DEFAULT__MAX_POOLED_PER_ROUTE", "23");
        initConfig();
        VertxHttpClientFactory factory = new VertxHttpClientFactory();
        factory.init(null);
        assertEquals(23, factory.buildPoolOptions().getHttp1MaxSize());
    }
}
