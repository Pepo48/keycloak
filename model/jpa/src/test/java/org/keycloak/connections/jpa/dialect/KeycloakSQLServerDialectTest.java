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

import java.util.Locale;
import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Checks SQL generation without executing SQL Server statements on the H2 bootstrap connection.
 */
public class KeycloakSQLServerDialectTest {

    private static final String INSERT = "insert into SqlServerConflictTestEntity (id, payload) values (1, 'first')";
    private StandardServiceRegistry registry;
    private SessionFactory sessionFactory;

    @Before
    public void createSessionFactory() {
        registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.connection.driver_class", "org.h2.Driver")
                .applySetting("hibernate.connection.url", "jdbc:h2:mem:" + UUID.randomUUID())
                .applySetting("hibernate.dialect", KeycloakSQLServerDialect.class.getName())
                .applySetting("hibernate.boot.allow_jdbc_metadata_access", false)
                .applySetting("hibernate.hbm2ddl.auto", "none")
                .applySetting("hibernate.session_factory.statement_inspector", (StatementInspector) sql -> {
                    throw new CapturedSql(sql);
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
    public void doNothingLocksMergeTarget() {
        assertLockedMerge(sql(INSERT + " on conflict (id) do nothing"));
    }

    @Test
    public void doUpdateLocksMergeTarget() {
        String sql = sql(INSERT + " on conflict (id) do update set payload = excluded.payload");
        assertLockedMerge(sql);
        assertTrue(sql.contains("when matched then update"));
    }

    @Test
    public void plainInsertSelectDoesNotLockSource() {
        String sql = sql("insert into SqlServerConflictTestEntity (id, payload)"
                + " select e.id + 1, e.payload from SqlServerConflictTestEntity e");
        assertTrue(sql.startsWith("insert into "));
        assertFalse(sql.contains("holdlock"));
    }

    @Test
    public void plainInsertHasNoMergeLock() {
        String sql = sql(INSERT);
        assertTrue(sql.startsWith("insert into "));
        assertFalse(sql.contains("holdlock"));
    }

    private String sql(String hql) {
        try (Session session = sessionFactory.openSession()) {
            session.beginTransaction();
            try {
                return assertThrows(CapturedSql.class, () -> session.createMutationQuery(hql).executeUpdate()).sql;
            } finally {
                session.getTransaction().rollback();
            }
        }
    }

    private void assertLockedMerge(String sql) {
        assertTrue(sql, sql.matches("(?s)^merge into \\S+ with \\(holdlock\\) \\S+ using .*;$"));
        assertEquals(sql, 1, sql.split("holdlock", -1).length - 1);
        assertEquals(sql, 1, sql.chars().filter(c -> c == ';').count());
    }

    private static class CapturedSql extends RuntimeException {
        private final String sql;

        private CapturedSql(String sql) {
            this.sql = sql.toLowerCase(Locale.ROOT);
        }
    }

    @Entity(name = "SqlServerConflictTestEntity")
    public static class ConflictEntity {
        @Id
        private int id;

        private String payload;
    }
}
