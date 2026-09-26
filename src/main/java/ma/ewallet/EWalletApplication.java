package ma.ewallet;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Entry point. {@code @SpringBootApplication} = configuration + auto-configuration
 * + component scan of this package and its sub-packages (that's why every class
 * lives under {@code ma.ewallet}). {@code @ConfigurationPropertiesScan} registers
 * {@code JwtProperties}.
 *
 * <p>Package layout is "by feature" (account, ledger, operation...) rather than
 * "by layer" (controllers, services...): everything about one topic sits together.</p>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class EWalletApplication {

    public static void main(String[] args) {
        SpringApplication.run(EWalletApplication.class, args);
    }
}
