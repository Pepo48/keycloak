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
package org.keycloak.connections.jpa.dialect;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.exception.ConstraintViolationException;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class KeycloakH2DialectTest {

    private static final String INSERT = "insert into H2ConflictTestEntity (id, payload) values (:id, :payload)";
    private static final String DO_NOTHING = INSERT + " on conflict (id) do nothing";
    private static final String DO_UPDATE = INSERT + " on conflict (id) do update set payload = excluded.payload";

    private final List<String> statements = new ArrayList<>();
    private StandardServiceRegistry registry;
    private SessionFactory sessionFactory;

    @Before
    public void createSessionFactory() {
        registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.connection.driver_class", "org.h2.Driver")
                .applySetting("hibernate.connection.url", "jdbc:h2:mem:" + UUID.randomUUID())
                .applySetting("hibernate.dialect", KeycloakH2Dialect.class.getName())
                .applySetting("hibernate.hbm2ddl.auto", "create-drop")
                .applySetting("hibernate.session_factory.statement_inspector", (StatementInspector) sql -> {
                    statements.add(sql);
                    return sql;
                })
                .build();
        sessionFactory = new MetadataSources(registry).addAnnotatedClass(ConflictEntity.class)
                .buildMetadata().buildSessionFactory();
    }

    @After
    public void closeSessionFactory() {
        if (sessionFactory != null) {
            sessionFactory.close();
        }
        if (registry != null) {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }

    @Test
    public void insertsWithConflictClause() {
        assertEquals(1, mutate(DO_NOTHING, 1, "first"));
        assertEquals("first", payload(1));
        assertTrue(statements.stream().anyMatch(sql -> sql.toLowerCase(Locale.ROOT).startsWith("merge into ")));
    }

    @Test
    public void duplicateDoesNothing() {
        assertEquals(1, mutate(DO_NOTHING, 1, "first"));
        assertEquals(0, mutate(DO_NOTHING, 1, "second"));
        assertEquals("first", payload(1));
    }

    @Test
    public void duplicateUpdates() {
        assertEquals(1, mutate(DO_UPDATE, 1, "first"));
        assertEquals(1, mutate(DO_UPDATE, 1, "second"));
        assertEquals("second", payload(1));
    }

    @Test
    public void unrelatedUniqueViolationIsNotIgnored() {
        mutate(DO_NOTHING, 1, "unique-payload");
        assertThrows(ConstraintViolationException.class, () -> mutate(DO_NOTHING, 2, "unique-payload"));
        assertEquals("unique-payload", payload(1));
    }

    @Test
    public void plainInsertStillRejectsDuplicates() {
        mutate(INSERT, 1, "first");
        assertThrows(ConstraintViolationException.class, () -> mutate(INSERT, 1, "second"));
        assertEquals("first", payload(1));
    }

    private int mutate(String hql, int id, String payload) {
        try (Session session = sessionFactory.openSession()) {
            session.beginTransaction();
            try {
                int count = session.createMutationQuery(hql).setParameter("id", id)
                        .setParameter("payload", payload).executeUpdate();
                session.getTransaction().commit();
                return count;
            } catch (RuntimeException e) {
                session.getTransaction().rollback();
                throw e;
            }
        }
    }

    private String payload(int id) {
        try (Session session = sessionFactory.openSession()) {
            return session.find(ConflictEntity.class, id).payload;
        }
    }

    @Entity(name = "H2ConflictTestEntity")
    public static class ConflictEntity {
        @Id
        private int id;

        @Column(unique = true)
        private String payload;
    }
}
