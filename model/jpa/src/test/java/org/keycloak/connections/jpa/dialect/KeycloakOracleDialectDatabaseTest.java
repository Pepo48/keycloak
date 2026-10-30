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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeNotNull;

/**
 * Opt-in real Oracle regression checks. Set keycloak.test.oracle.url/user/password for a disposable database.
 */
public class KeycloakOracleDialectDatabaseTest {

    private static final String INSERT = "insert into OracleDatabaseConflictEntity (id, payload) values (:id, :payload)";
    private static final String DO_NOTHING = INSERT + " on conflict (id) do nothing";
    private StandardServiceRegistry registry;
    private SessionFactory sessionFactory;

    @Before
    public void createSessionFactory() {
        String url = System.getProperty("keycloak.test.oracle.url");
        assumeNotNull(url);
        registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.connection.driver_class", "oracle.jdbc.OracleDriver")
                .applySetting("hibernate.connection.url", url)
                .applySetting("hibernate.connection.username", System.getProperty("keycloak.test.oracle.user"))
                .applySetting("hibernate.connection.password", System.getProperty("keycloak.test.oracle.password"))
                .applySetting("hibernate.dialect", KeycloakOracleDialect.class.getName())
                .applySetting("hibernate.hbm2ddl.auto", "create-drop")
                .build();
        sessionFactory = new MetadataSources(registry).addAnnotatedClass(ConflictEntity.class).addAnnotatedClass(CompositeEntity.class)
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
    public void duplicateDoesNothingAndTransactionRemainsUsable() {
        assertEquals(1, mutate(DO_NOTHING, 1, "first"));
        try (Session session = sessionFactory.openSession()) {
            session.beginTransaction();
            assertEquals(0, execute(session, DO_NOTHING, 1, "second"));
            assertEquals(1, execute(session, DO_NOTHING, 2, "third"));
            session.getTransaction().commit();
        }
        assertEquals("first", payload(1));
        assertEquals("third", payload(2));
    }

    @Test
    public void concurrentDuplicateDoesNothingAfterCompetingCommit() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Session first = sessionFactory.openSession()) {
            first.beginTransaction();
            assertEquals(1, execute(first, DO_NOTHING, 1, "winner"));
            CountDownLatch started = new CountDownLatch(1);
            Future<Integer> competing = executor.submit(() -> {
                try (Session second = sessionFactory.openSession()) {
                    second.beginTransaction();
                    started.countDown();
                    int rows = execute(second, DO_NOTHING, 1, "loser");
                    assertEquals(1, execute(second, DO_NOTHING, 2, "after-conflict"));
                    second.getTransaction().commit();
                    return rows;
                }
            });
            assertTrue(started.await(10, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> competing.get(1, TimeUnit.SECONDS));
            first.getTransaction().commit();
            assertEquals(Integer.valueOf(0), competing.get(30, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
        }
        assertEquals("winner", payload(1));
        assertEquals("after-conflict", payload(2));
    }

    @Test
    public void unrelatedUniqueViolationIsNotIgnored() {
        mutate(DO_NOTHING, 1, "unique-payload");
        assertThrows(ConstraintViolationException.class, () -> mutate(DO_NOTHING, 2, "unique-payload"));
    }

    @Test
    public void doUpdateAndPlainInsertKeepTheirBehavior() {
        mutate(DO_NOTHING, 1, "first");
        assertEquals(1, mutate(INSERT + " on conflict (id) do update set payload = excluded.payload", 1, "updated"));
        assertEquals("updated", payload(1));
        assertThrows(ConstraintViolationException.class, () -> mutate(INSERT, 1, "plain-duplicate"));
    }

    @Test
    public void compositeConflictSuppressesOnlyItsOwnUniqueConstraint() {
        String hql = "insert into OracleCompositeConflict (id, realmId, externalId) values (:id, :realm, :external)"
                + " on conflict (realmId, externalId) do nothing";
        try (Session session = sessionFactory.openSession()) {
            session.beginTransaction();
            assertEquals(1, session.createMutationQuery(hql).setParameter("id", 1)
                    .setParameter("realm", 7).setParameter("external", "key").executeUpdate());
            assertEquals(0, session.createMutationQuery(hql).setParameter("id", 2)
                    .setParameter("realm", 7).setParameter("external", "key").executeUpdate());
            session.getTransaction().commit();
        }
        try (Session session = sessionFactory.openSession()) {
            session.beginTransaction();
            try {
                assertThrows(ConstraintViolationException.class, () -> session.createMutationQuery(hql).setParameter("id", 1)
                        .setParameter("realm", 8).setParameter("external", "different-key").executeUpdate());
            } finally {
                session.getTransaction().rollback();
            }
        }
    }

    private int mutate(String hql, int id, String payload) {
        try (Session session = sessionFactory.openSession()) {
            session.beginTransaction();
            try {
                int count = execute(session, hql, id, payload);
                session.getTransaction().commit();
                return count;
            } catch (RuntimeException e) {
                session.getTransaction().rollback();
                throw e;
            }
        }
    }

    private int execute(Session session, String hql, int id, String payload) {
        return session.createMutationQuery(hql).setParameter("id", id).setParameter("payload", payload).executeUpdate();
    }

    private String payload(int id) {
        try (Session session = sessionFactory.openSession()) {
            return session.find(ConflictEntity.class, id).payload;
        }
    }

    @Entity(name = "OracleDatabaseConflictEntity")
    @Table(name = "\"Oracle.Conflict\"")
    public static class ConflictEntity {
        @Id
        private int id;
        @Column(unique = true)
        private String payload;
    }

    @Entity(name = "OracleCompositeConflict")
    @Table(uniqueConstraints = @UniqueConstraint(columnNames = { "realm_id", "external_id" }))
    public static class CompositeEntity {
        @Id
        private int id;
        @Column(name = "realm_id")
        private int realmId;
        @Column(name = "external_id")
        private String externalId;
    }
}
