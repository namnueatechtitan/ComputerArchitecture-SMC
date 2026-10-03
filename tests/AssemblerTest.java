import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.FileSystemException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Encoding oracles and real CLI processes; no external testing framework. */
public final class AssemblerTest {
    private static final int[] COUNTDOWN = {
        8454151, 9043971, 655361, 16842754, 16842749,
        29360128, 25165824, 5, -1, 2
    };
    private static final List<Path> TEMPORARY_FILES = new ArrayList<>();
    private static int corePassed;
    private static int cliPassed;

    private AssemblerTest() {
    }

    public static void main(String[] args) throws Exception {
        try {
            testEncoding();
            testErrors();
            testLargePrograms();
            testCli();
        } finally {
            cleanupTemporaryFiles();
        }
        System.out.println("PASS: " + corePassed + " assembler cases; " + cliPassed + " CLI cases");
    }

    /** Retry short-lived Windows file locks, with a fixed per-file bound. */
    private static void cleanupTemporaryFiles() throws Exception {
        // Delete only individually recorded, uniquely named test files.
        // No directory traversal or recursive deletion is performed.
        for (Path path : TEMPORARY_FILES) {
            for (int attempt = 0; attempt < 10; attempt++) {
                try {
                    Files.deleteIfExists(path);
                    break;
                } catch (FileSystemException error) {
                    if (attempt == 9) {
                        throw error;
                    }
                    Thread.sleep(50);
                }
            }
        }
    }

    /** Expected words are literal oracles, not calls to encoding helper methods. */
    private static void testEncoding() throws Exception {
        expectWords("every opcode and format", new int[] {
            655363, 4849667, 8454149, 12713983,
            17498109, 21626880, 25165824, 29360128
        }, "    add 1 2 3", "    nand 1 2 3", "    lw 0 1 5", "    sw 0 1 -1",
                "    beq 1 2 -3", "    jalr 1 2", "    halt", "    noop");
        expectWords("maximum registers including identical jalr", new int[] {4128775, 25100288},
                "    add 7 7 7", "    jalr 7 7");
        expectWords("register zero remains a valid operand", new int[] {655360, 20971520},
                "    add 1 2 0", "    jalr 0 0");
        expectWords("signed 32-bit fill and decimal signs", new int[] {
            5, -1, Integer.MIN_VALUE, Integer.MAX_VALUE, 5, 0
        }, "    .fill 5", "    .fill -1", "    .fill -2147483648", "    .fill 2147483647",
                "    .fill +0005", "    .fill -0");
        expectWords("forward absolute and PC-relative labels",
                new int[] {10616835, 12648451, 16777216, 42},
                "    lw 4 2 data", "    sw 0 1 data", "    beq 0 0 data", "data .fill 42");
        expectWords("backward labels and symbolic fill",
                new int[] {9, 8454144, 12648448, 16842748, 0},
                "head .fill 9", "    lw 0 1 head", "    sw 0 1 head", "    beq 0 0 head",
                "    .fill head");
        expectWords("branches in both directions", new int[] {29360128, 16777217, 16842749, 25165824},
                "top noop", "    beq 0 0 end", "    beq 0 0 top", "end halt");
        expectWords("forward and backward fill", new int[] {1, 0},
                "here .fill next", "next .fill here");
        expectWords("self branch", new int[] {16842751}, "self beq 0 0 self");
        expectWords("numeric beq is already a displacement", new int[] {29360128, 16777218},
                "    noop", "    beq 0 0 2");
        expectWords("case-sensitive symbols", new int[] {1, 0}, "A .fill a", "a .fill A");
        expectWords("opcode spelling used as a label", new int[] {0}, "add .fill add");
        expectWords("trailing comments", new int[] {655363, 25165824},
                "    add 1 2 3 decrement counter", "    halt end of program");
        expectWords("empty source", new int[0]);

        String[] operations = {"lw", "sw", "beq"};
        int[] lowerWords = {8486912, 12681216, 16875520};
        int[] upperWords = {8486911, 12681215, 16875519};
        for (int i = 0; i < operations.length; i++) {
            // regB=1 for all three instructions; positive/negative boundaries.
            expectWords(operations[i] + " lower numeric boundary", new int[] {lowerWords[i]},
                    "    " + operations[i] + " 0 1 -32768");
            expectWords(operations[i] + " upper numeric boundary", new int[] {upperWords[i]},
                    "    " + operations[i] + " 0 1 32767");
        }

        List<String> expectedLines = Files.readAllLines(Paths.get("tests", "fixtures", "countdown.expected.mc"),
                StandardCharsets.UTF_8);
        if (!Arrays.equals(COUNTDOWN, parseWords(expectedLines))) {
            throw new AssertionError("countdown expected fixture differs from the instructor's ten values");
        }
        try (BufferedReader reader = Files.newBufferedReader(Paths.get("tests", "fixtures", "countdown.as"),
                StandardCharsets.UTF_8)) {
            assertWords("countdown instructor values", COUNTDOWN, Assembler.assemble(reader));
        }
    }

    /** Verify each failure category and its physical source line. */
    private static void testErrors() throws Exception {
        expectRejected("duplicate label at address zero", 2, "duplicate label 'dup'",
                "dup .fill 1", "dup halt");
        for (String opcode : new String[] {"lw", "sw", "beq"}) {
            expectRejected(opcode + " undefined label", 1, "undefined label 'absent'",
                    "    " + opcode + " 0 1 absent");
            for (String offset : new String[] {"-32769", "32768"}) {
                expectRejected(opcode + " rejected offset " + offset, 1, "outside signed 16-bit range",
                        "    " + opcode + " 0 1 " + offset);
            }
        }
        expectRejected("undefined fill", 1, "undefined label 'absent'", "    .fill absent");
        expectRejected("case mismatch", 2, "undefined label 'data'", "Data .fill 1", "    .fill data");
        expectRejected("unknown opcode", 1, "invalid opcode 'xor'", "    xor 0 1 2");

        String[][] registerErrors = {
            {"R A negative", "    add -1 0 0", "outside range [0, 7]"},
            {"R B too large", "    add 0 8 0", "outside range [0, 7]"},
            {"R destination too large", "    nand 0 0 8", "outside range [0, 7]"},
            {"numeric register prefix", "    add 12abc 0 0", "invalid register"},
            {"register decimal overflow", "    add 2147483648 0 0", "outside signed 32-bit range"},
            {"symbolic register", "    add zero 0 0", "invalid register"},
            {"lw A too large", "    lw 8 0 0", "outside range [0, 7]"},
            {"lw B negative", "    lw 0 -1 0", "outside range [0, 7]"},
            {"sw B too large", "    sw 0 8 0", "outside range [0, 7]"},
            {"beq A negative", "    beq -1 0 0", "outside range [0, 7]"},
            {"beq B too large", "    beq 0 8 0", "outside range [0, 7]"},
            {"jalr A too large", "    jalr 8 0", "outside range [0, 7]"},
            {"jalr B negative", "    jalr 0 -1", "outside range [0, 7]"}
        };
        for (String[] test : registerErrors) {
            expectRejected(test[0], 1, test[2], test[1]);
        }
        for (String token : new String[] {"12abc", "0x10", "1.5", "+", "--1"}) {
            expectRejected("malformed fill " + token, 1, "invalid operand", "    .fill " + token);
        }
        for (String token : new String[] {"2147483648", "-2147483649"}) {
            expectRejected("fill decimal overflow " + token, 1, "outside signed 32-bit range",
                    "    .fill " + token);
        }
        expectRejected("malformed offset", 1, "invalid operand", "    lw 0 1 12abc");
        expectRejected("offset decimal overflow", 1, "outside signed 32-bit range", "    beq 0 0 2147483648");
        expectRejected("invalid symbolic operand", 1, "invalid operand", "    sw 0 1 bad_x");
    }

    /** Generate long sources in memory to exercise real address/range limits. */
    private static void testLargePrograms() throws Exception {
        String[] full = noops(65536);
        full[0] = "    .fill last";
        full[65535] = "last halt";
        expectLarge("65536 words and highest fill address", full, 65535, 25165824);
        expectRejected("65537 words rejected", 65537, "program exceeds 65536", noops(65537));

        String[] absoluteBoundary = noops(32768);
        absoluteBoundary[0] = "    lw 0 1 edge";
        absoluteBoundary[32767] = "edge halt";
        expectLarge("lw symbolic address 32767", absoluteBoundary, 8486911, 25165824);

        String[] absoluteTooFar = noops(32769);
        absoluteTooFar[0] = "    lw 0 1 edge";
        absoluteTooFar[32768] = "edge halt";
        expectRejected("lw symbolic address 32768", 1, "offset 32768", absoluteTooFar);
        absoluteTooFar[0] = "    sw 0 1 edge";
        expectRejected("sw symbolic address 32768", 1, "offset 32768", absoluteTooFar);

        String[] forwardBoundary = noops(32769);
        forwardBoundary[0] = "    beq 0 0 edge";
        forwardBoundary[32768] = "edge halt";
        expectLarge("beq symbolic forward 32767", forwardBoundary, 16809983, 25165824);
        String[] forwardTooFar = noops(32770);
        forwardTooFar[0] = "    beq 0 0 edge";
        forwardTooFar[32769] = "edge halt";
        expectRejected("beq symbolic forward 32768", 1, "offset 32768", forwardTooFar);

        String[] backwardBoundary = noops(32768);
        backwardBoundary[0] = "head noop";
        backwardBoundary[32767] = "    beq 0 0 head";
        expectLarge("beq symbolic backward -32768", backwardBoundary, 29360128, 16809984);
        String[] backwardTooFar = noops(32769);
        backwardTooFar[0] = "head noop";
        backwardTooFar[32768] = "    beq 0 0 head";
        expectRejected("beq symbolic backward -32769", 32769, "offset -32769", backwardTooFar);
    }

    /** These tests invoke main in a child JVM and check its actual exit code. */
    private static void testCli() throws Exception {
        Path countdownOutput = temporaryFile(".mc");
        CliResult countdown = runCli("tests/fixtures/countdown.as", countdownOutput.toString());
        expectCliSuccess("countdown exact signed-decimal output", countdown, countdownOutput, COUNTDOWN);

        cliSuccessSource("signed fill output", "    .fill -2147483648\n    .fill 2147483647\n    .fill -1",
                new int[] {Integer.MIN_VALUE, Integer.MAX_VALUE, -1});
        cliSuccessSource("no final newline", "    halt", new int[] {25165824});
        cliSuccessSource("CRLF input", "    lw 0 1 value\r\n    halt\r\nvalue .fill 5\r\n",
                new int[] {8454146, 25165824, 5});
        cliSuccessSource("empty input", "", new int[0]);

        String[][] failures = {
            {"duplicate", "dup .fill 1\ndup halt", "duplicate label", "false"},
            {"late error preserves existing output", "    noop\n    halt\n    lw 0 1 absent", "undefined label", "true"},
            {"offset limit", "    beq 0 0 32768", "outside signed 16-bit range", "false"},
            {"invalid register", "    add 0 8 1", "outside range [0, 7]", "false"},
            {"invalid opcode", "    xor 0 1 2", "invalid opcode", "false"},
            {"invalid label", "toolong noop", "invalid label", "false"},
            {"malformed decimal", "    .fill 12abc", "invalid operand", "false"},
            {"blank line", "\n    halt", "missing opcode", "false"}
        };
        for (String[] test : failures) {
            cliFailureSource(test[0], test[1], test[2], Boolean.parseBoolean(test[3]));
        }
        cliFailureSource("program capacity", String.join("\n", noops(65537)), "program exceeds 65536", false);

        for (String[] arguments : new String[][] {{}, {"one"}, {"one", "two", "three"}}) {
            CliResult result = runCli(arguments);
            assertCliFailure("usage", result, "usage:");
            passCli("usage with " + arguments.length + " arguments", "exit=1; stderr only");
        }

        Path missingInput = temporaryFile(".as");
        Files.delete(missingInput);
        Path missingOutput = outputFor(missingInput);
        CliResult missing = runCli(missingInput.toString(), missingOutput.toString());
        assertCliFailure("missing input", missing, "error:");
        if (Files.exists(missingOutput)) {
            throw new AssertionError("missing input created an output file");
        }
        passCli("missing input", "exit=1; output absent");

        Path source = temporaryFile(".as");
        byte[] sourceBytes = "    halt".getBytes(StandardCharsets.UTF_8);
        Files.write(source, sourceBytes);
        Path invalidOutput = source.resolve("child.mc"); // A file cannot be an output directory.
        CliResult badOutput = runCli(source.toString(), invalidOutput.toString());
        assertCliFailure("invalid output path", badOutput, "error:");
        if (!Arrays.equals(sourceBytes, Files.readAllBytes(source))) {
            throw new AssertionError("output I/O failure changed the source");
        }
        passCli("invalid output path", "exit=1; source preserved");

        for (Path alias : new Path[] {source, source.resolveSibling(".").resolve(source.getFileName())}) {
            CliResult sameFile = runCli(source.toString(), alias.toString());
            assertCliFailure("source overwrite", sameFile, "input and output must be different files");
            if (!Arrays.equals(sourceBytes, Files.readAllBytes(source))) {
                throw new AssertionError("input/output alias changed the source");
            }
            passCli("input/output alias", "exit=1; source preserved");
        }
    }

    private static int[] assembleLines(String... lines) throws Exception {
        try (BufferedReader reader = new BufferedReader(new StringReader(String.join("\n", lines)))) {
            return Assembler.assemble(reader);
        }
    }

    private static void expectWords(String name, int[] expected, String... source) throws Exception {
        assertWords(name, expected, assembleLines(source));
    }

    private static void assertWords(String name, int[] expected, int[] actual) {
        if (!Arrays.equals(expected, actual)) {
            throw new AssertionError(name + ": expected=" + Arrays.toString(expected)
                    + "; actual=" + Arrays.toString(actual));
        }
        corePassed++;
        System.out.println("PASS " + name + ": expected=actual " + Arrays.toString(actual));
    }

    private static void expectRejected(String name, int lineNumber, String reason, String... source)
            throws Exception {
        try {
            assembleLines(source);
        } catch (Assembler.AssemblyException error) {
            if (!error.getMessage().startsWith("line " + lineNumber + ": ")
                    || !error.getMessage().contains(reason)) {
                throw new AssertionError(name + ": expected line " + lineNumber + " and '" + reason
                        + "'; actual=" + error.getMessage());
            }
            corePassed++;
            System.out.println("PASS " + name + ": expected rejection '" + reason
                    + "'; actual=" + error.getMessage());
            return;
        }
        throw new AssertionError(name + ": expected rejection, but assembly succeeded");
    }

    private static String[] noops(int count) {
        String[] source = new String[count];
        Arrays.fill(source, "    noop");
        return source;
    }

    private static void expectLarge(String name, String[] source, int first, int last) throws Exception {
        int[] actual = assembleLines(source);
        if (actual.length != source.length || actual[0] != first || actual[actual.length - 1] != last) {
            throw new AssertionError(name + ": wrong length or endpoint word");
        }
        for (int i = 1; i < actual.length - 1; i++) {
            if (actual[i] != 29360128) {
                throw new AssertionError(name + ": wrong noop at address " + i);
            }
        }
        corePassed++;
        System.out.println("PASS " + name + ": " + actual.length + " words; expected=actual endpoints "
                + first + ", " + last + "; all interior words match");
    }

    private static int[] parseWords(List<String> lines) {
        int[] words = new int[lines.size()];
        for (int i = 0; i < words.length; i++) {
            words[i] = Integer.parseInt(lines.get(i));
        }
        return words;
    }

    private static Path temporaryFile(String suffix) throws Exception {
        Path file = Files.createTempFile(Paths.get("build", "parser-step"), "assembler-test-", suffix);
        TEMPORARY_FILES.add(file);
        return file;
    }

    private static Path outputFor(Path source) {
        Path output = source.resolveSibling(source.getFileName().toString() + ".mc");
        if (Files.exists(output)) {
            throw new AssertionError("test output already exists: " + output);
        }
        TEMPORARY_FILES.add(output);
        return output;
    }

    private static CliResult runCli(String... args) throws Exception {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        List<String> command = new ArrayList<>();
        command.add(Paths.get(System.getProperty("java.home"), "bin", executable).toString());
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add("Assembler");
        command.addAll(Arrays.asList(args));
        Process process = new ProcessBuilder(command).start();
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("CLI did not finish within 10 seconds: " + command);
        }
        String stdout = readUtf8(process.getInputStream());
        String stderr = readUtf8(process.getErrorStream());
        System.out.println("RUN " + command + " => exit=" + process.exitValue());
        return new CliResult(process.exitValue(), stdout, stderr);
    }

    private static String readUtf8(InputStream input) throws Exception {
        try (InputStream stream = input; ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int length;
            while ((length = stream.read(buffer)) != -1) {
                bytes.write(buffer, 0, length);
            }
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void assertCliFailure(String name, CliResult actual, String diagnostic) {
        if (actual.exitCode != 1 || !actual.stdout.isEmpty() || !actual.stderr.contains(diagnostic)) {
            throw new AssertionError(name + ": expected exit=1, empty stdout, stderr containing '"
                    + diagnostic + "'; actual exit=" + actual.exitCode + ", stdout=" + actual.stdout
                    + ", stderr=" + actual.stderr);
        }
    }

    private static void expectCliSuccess(String name, CliResult actual, Path output, int[] expected)
            throws Exception {
        StringBuilder expectedText = new StringBuilder();
        for (int word : expected) {
            expectedText.append(word).append(System.lineSeparator());
        }
        if (actual.exitCode != 0 || !actual.stdout.isEmpty() || !actual.stderr.isEmpty()
                || !Arrays.equals(expectedText.toString().getBytes(StandardCharsets.UTF_8),
                        Files.readAllBytes(output))) {
            throw new AssertionError(name + ": expected exit=0, empty diagnostics, exact decimal output; "
                    + "actual exit=" + actual.exitCode + ", stdout=" + actual.stdout + ", stderr=" + actual.stderr);
        }
        passCli(name, "exit=0; exact output matched; stdout/stderr empty");
    }

    private static void cliSuccessSource(String name, String text, int[] expected) throws Exception {
        Path source = temporaryFile(".as");
        Files.write(source, text.getBytes(StandardCharsets.UTF_8));
        Path output = outputFor(source);
        expectCliSuccess(name, runCli(source.toString(), output.toString()), output, expected);
    }

    private static void cliFailureSource(String name, String text, String diagnostic, boolean preserveOutput)
            throws Exception {
        Path source = temporaryFile(".as");
        Files.write(source, text.getBytes(StandardCharsets.UTF_8));
        Path output = outputFor(source);
        byte[] sentinel = "keep existing output\r\n".getBytes(StandardCharsets.UTF_8);
        if (preserveOutput) {
            Files.write(output, sentinel);
        }
        CliResult actual = runCli(source.toString(), output.toString());
        assertCliFailure(name, actual, diagnostic);
        if (preserveOutput) {
            if (!Arrays.equals(sentinel, Files.readAllBytes(output))) {
                throw new AssertionError(name + ": existing output was modified");
            }
        } else if (Files.exists(output)) {
            throw new AssertionError(name + ": invalid assembly created output");
        }
        passCli(name, "exit=1; stderr=" + actual.stderr.trim() + "; output "
                + (preserveOutput ? "preserved" : "absent"));
    }

    private static void passCli(String name, String actual) {
        cliPassed++;
        System.out.println("PASS CLI " + name + ": " + actual);
    }

    private static final class CliResult {
        final int exitCode;
        final String stdout;
        final String stderr;

        private CliResult(int exitCode, String stdout, String stderr) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
        }
    }
}
