package dev.claudev.app;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Verifies the Spring wiring alone — does not call {@code main()}, so it never launches JavaFX.
 * DataSource autoconfiguration is excluded because {@code persistence}'s real SQLite wiring
 * (WAL/busy_timeout DataSource bean) is still M0 backlog — see {@code SqlitePragmaConfigurer}.
 */
@SpringBootTest(
        classes = ClaudevApplication.class,
        properties = {
                "spring.main.web-application-type=none",
                "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration"
        })
class ClaudevApplicationTests {

    @Test
    void contextLoads() {
    }
}
