package org.keycloak.quarkus.runtime.configuration.mappers;

import org.keycloak.config.ConfigKeystoreOptions;

import static org.keycloak.quarkus.runtime.configuration.mappers.PropertyMapper.fromOption;

final class KeystorePropertyMappers {

    private KeystorePropertyMappers() {
    }

    public static PropertyMapper[] getKeystorePropertyMappers() {
        return new PropertyMapper[] {
                fromOption(ConfigKeystoreOptions.KEYSTORE_PATH)
                        .to("smallrye.config.source.keystore.test.path")
                        .paramLabel("config-keystore")
                        .build(),
                fromOption(ConfigKeystoreOptions.KEYSTORE_PASSWORD)
                        .to("smallrye.config.source.keystore.test.password")
                        .paramLabel("config-keystore-password")
                        .build()
        };
    }

}
