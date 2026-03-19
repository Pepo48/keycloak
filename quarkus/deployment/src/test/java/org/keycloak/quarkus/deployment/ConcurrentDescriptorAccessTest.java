package org.keycloak.quarkus.deployment;

import java.util.ConcurrentModificationException;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import org.hibernate.jpa.boot.internal.ParsedPersistenceXmlDescriptor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the race condition between KeycloakProcessor.configurePersistenceUnits()
 * (which calls descriptor.addClasses()) and Quarkus's
 * HibernateOrmProcessor.contributePersistenceXmlToJpaModel()
 * (which iterates descriptor.getManagedClassNames() via new TreeSet<>()).
 *
 * This reproduces the exact ConcurrentModificationException from CI:
 *
 *   at java.util.ArrayList$Itr.checkForComodification
 *   at java.util.ArrayList$Itr.next
 *   at java.util.AbstractCollection.addAll
 *   at java.util.TreeSet.addAll
 *   at java.util.TreeSet.<init>
 *   at JpaModelPersistenceUnitContributionBuildItem.<init>:25
 *   at HibernateOrmProcessor.contributePersistenceXmlToJpaModel:463
 */
class ConcurrentDescriptorAccessTest {

    @Test
    void descriptorAddClassesConcurrentWithGetManagedClassNamesIteration()
            throws InterruptedException {

        // Simulate the shared ParsedPersistenceXmlDescriptor
        // from PersistenceXmlDescriptorBuildItem
        ParsedPersistenceXmlDescriptor descriptor =
                new ParsedPersistenceXmlDescriptor(null);

        // Pre-populate with entity classes
        // (Keycloak's default PU has ~64 entities)
        for (int i = 0; i < 64; i++) {
            descriptor.addClasses(
                    "org.keycloak.models.jpa.entities.Entity" + i);
        }

        AtomicBoolean cmeDetected = new AtomicBoolean(false);
        CountDownLatch startLatch = new CountDownLatch(1);

        // Thread A: simulates Quarkus's
        // HibernateOrmProcessor.contributePersistenceXmlToJpaModel()
        // which calls:
        //   new JpaModelPersistenceUnitContributionBuildItem(
        //       ..., descriptor.getManagedClassNames(), ...)
        // The constructor does: new TreeSet<>(explicitlyListedClassNames)
        Thread reader = new Thread(() -> {
            try { startLatch.await(); } catch (InterruptedException e) { return; }
            for (int i = 0; i < 100_000 && !cmeDetected.get(); i++) {
                try {
                    new TreeSet<>(descriptor.getManagedClassNames());
                } catch (ConcurrentModificationException e) {
                    System.err.println("ConcurrentModificationException caught on iteration " + i + ": " + e);
                    cmeDetected.set(true);
                }
            }
        }, "reader-contributePersistenceXmlToJpaModel");

        // Thread B: simulates Keycloak's
        // KeycloakProcessor.configureDefaultPersistenceUnitEntities()
        // which calls: descriptor.addClasses(targetName)
        Thread writer = new Thread(() -> {
            try { startLatch.await(); } catch (InterruptedException e) { return; }
            for (int i = 0; i < 100_000 && !cmeDetected.get(); i++) {
                descriptor.addClasses("race.Entity" + i);
            }
        }, "writer-configurePersistenceUnits");

        reader.start();
        writer.start();
        startLatch.countDown();

        reader.join(10_000);
        writer.join(10_000);

        assertTrue(cmeDetected.get(),
            "ConcurrentModificationException must be triggered when "
            + "descriptor.addClasses() runs concurrently with "
            + "new TreeSet<>(descriptor.getManagedClassNames()). "
            + "This proves the race condition between "
            + "KeycloakProcessor.configurePersistenceUnits() and "
            + "HibernateOrmProcessor.contributePersistenceXmlToJpaModel().");
    }
}
