package edu.hitsz.canteen.web;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MySqlConnectionPolicyTest {
    @Test
    void formalPortCannotBeOverriddenAndIsolationRequiresOwnedProjectRoot() throws Exception {
        ProjectPaths formal = new ProjectPaths(null);
        assertEquals(13306, MySqlConnectionPolicy.port(formal, 0, false));
        assertThrows(IllegalStateException.class,
                () -> MySqlConnectionPolicy.port(formal, 13307, true));

        Path parent = formal.root().resolve("java/web/target");
        Files.createDirectories(parent);
        Path isolated = parent.resolve("multiuser-mysql-" + UUID.randomUUID());
        Path data = Files.createDirectories(isolated.resolve("data"));
        ProjectPaths candidate = new ProjectPaths(isolated.toString());
        try {
            assertThrows(IllegalStateException.class,
                    () -> MySqlConnectionPolicy.port(candidate, 13307, true));
            Path owner = data.resolve(".candidate-isolation-owner");
            Files.writeString(owner, "wrong-owner\n", StandardCharsets.UTF_8);
            assertThrows(IllegalStateException.class,
                    () -> MySqlConnectionPolicy.port(candidate, 13307, true));
            Files.writeString(owner, "canteen-candidate-isolated-v1\n", StandardCharsets.UTF_8);
            assertThrows(IllegalStateException.class,
                    () -> MySqlConnectionPolicy.port(candidate, 0, true));
            assertThrows(IllegalStateException.class,
                    () -> MySqlConnectionPolicy.port(candidate, 13306, true));
            assertThrows(IllegalStateException.class,
                    () -> MySqlConnectionPolicy.port(candidate, 13307, false));
            assertEquals(13307, MySqlConnectionPolicy.port(candidate, 13307, true));
            Path alias = Path.of("Z:/README.md");
            if (Files.isRegularFile(alias) && Files.isSameFile(alias, formal.root().resolve("README.md"))) {
                ProjectPaths sameCandidateThroughAlias = new ProjectPaths(
                        "Z:/java/web/target/" + isolated.getFileName());
                assertEquals(13307, MySqlConnectionPolicy.port(sameCandidateThroughAlias, 13307, true));
            }
        } finally {
            Files.deleteIfExists(data.resolve(".candidate-isolation-owner"));
            Files.deleteIfExists(data);
            Files.deleteIfExists(isolated);
        }
    }
}
