package com.beavent.backend.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** The API contract's Problem.code enum and {@link ProblemCode} must list exactly the same codes. */
class ProblemCodeContractTest {

    @Test
    void javaCodesMatchTheOpenApiContract() throws IOException {
        String spec = Files.readString(Path.of("src/main/openapi/common/schemas.yaml"));
        int start = spec.indexOf("    code:\n      type: string\n      enum:\n");
        assertThat(start).as("Problem.code enum in common/schemas.yaml").isNotNegative();
        Matcher matcher =
                Pattern.compile("^ {8}- ([A-Z_]+)$", Pattern.MULTILINE).matcher(spec.substring(start));
        Set<String> contract = new java.util.TreeSet<>();
        int expectedNext = -1;
        while (matcher.find()) {
            if (expectedNext >= 0 && matcher.start() != expectedNext) {
                break; // end of the enum block
            }
            contract.add(matcher.group(1));
            expectedNext = matcher.end() + 1;
        }
        Set<String> java = Arrays.stream(ProblemCode.values())
                .map(Enum::name)
                .collect(Collectors.toCollection(java.util.TreeSet::new));
        assertThat(java).isEqualTo(contract);
    }
}
