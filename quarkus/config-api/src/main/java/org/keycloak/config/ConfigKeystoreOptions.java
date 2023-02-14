package org.keycloak.config;

public class ConfigKeystoreOptions {

    public static final Option KEYSTORE_PATH = new OptionBuilder<>("config-keystore", String.class)
            .category(OptionCategory.KEYSTORE)
            .description("Specifies a path to the keystore.")
            .build();

    public static final Option KEYSTORE_PASSWORD = new OptionBuilder<>("config-keystore-password", String.class)
            .category(OptionCategory.KEYSTORE)
            .description("Specifies a password to the keystore.")
            .build();

}
