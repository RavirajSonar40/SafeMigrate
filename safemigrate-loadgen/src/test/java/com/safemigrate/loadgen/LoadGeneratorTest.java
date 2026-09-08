package com.safemigrate.loadgen;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LoadGeneratorTest {

    @Test
    void shouldGenerateTrafficWithoutErrors() throws InterruptedException {
        LoadGenerator generator = new LoadGenerator(
                "jdbc:postgresql://localhost:5432/safemigrate_test",
                "postgres",
                "password",
                4,
                50 // 50 ops/sec
        );

        generator.start();
        Thread.sleep(2000); // Run for 2 seconds
        generator.stop();

        assertThat(generator.getSuccessRequests()).isGreaterThan(20);
        assertThat(generator.getErrorRequests()).isZero();
    }
}
