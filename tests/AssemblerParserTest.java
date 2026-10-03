import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

/** Run with Java directly; no testing library or assertion flag is required. */
public final class AssemblerParserTest {
    private static int passed;

    private AssemblerParserTest() {
    }

    public static void main(String[] args) throws Exception {
        expectParsed("leading spaces", "        lw 0 1 five", 1, "", "lw", "0", "1", "five");
        expectParsed("leading tab", "\tlw\t1\t2\t3", 2, "", "lw", "1", "2", "3");
        expectParsed("labeled instruction", "start add 1 2 1", 3, "start", "add", "1", "2", "1");
        expectParsed("negative fill", "negone .fill -1", 4, "negone", ".fill", "-1");
        expectParsed("mixed whitespace and uppercase label", "Loop\tbeq \t0\t0  start", 5,
                "Loop", "beq", "0", "0", "start");
        expectParsed("negative offset token", "    beq 0 0 -3", 6, "", "beq", "0", "0", "-3");
        expectParsed("jalr operand count", "    jalr 1 2", 7, "", "jalr", "1", "2");
        expectParsed("halt trailing comment", "    halt end of program", 8, "", "halt");
        expectParsed("add trailing comment", "    add 0 1 2 # ignored text", 9,
                "", "add", "0", "1", "2");
        expectParsed("label named like opcode", "add noop", 10, "add", "noop");
        expectParsed("six-character label", "abcdef noop", 11, "abcdef", "noop");
        expectParsed("digits in label and symbolic fill", "x1 .fill start", 12, "x1", ".fill", "start");
        expectParsed("store and positive offset", "    sw 0 1 32767", 13,
                "", "sw", "0", "1", "32767");
        expectParsed("nand operand count", "    nand 1 2 3", 14, "", "nand", "1", "2", "3");

        expectRejected("unindented halt", "halt", 21, "missing opcode after label");
        expectRejected("unindented add", "add 1 2 3", 22, "invalid opcode '1'");
        expectRejected("empty line", "", 23, "missing opcode");
        expectRejected("whitespace-only line", "   \t  ", 24, "missing opcode");
        expectRejected("comment-only line", "# comment", 25, "invalid label");
        expectRejected("seven-character label", "toolong noop", 26, "invalid label");
        expectRejected("label starts with digit", "1start noop", 27, "invalid label");
        expectRejected("punctuation in label", "bad! noop", 28, "invalid label");
        expectRejected("underscore in label", "bad_x noop", 29, "invalid label");
        expectRejected("unsupported opcode", "    xor 0 1 2", 30, "invalid opcode 'xor'");
        expectRejected("uppercase opcode", "    HALT", 31, "invalid opcode 'HALT'");
        expectRejected("missing operand", "    lw 0 1", 32, "needs 3 operands; found 2");

        checkCountdownFixture();
        System.out.println("PASS: " + passed + " parser cases (14 valid, 12 rejected, 10 countdown lines)");
    }

    /** Compare independently specified fields and diagnostic source metadata. */
    private static void expectParsed(String name, String source, int lineNumber,
            String label, String opcode, String... operands) throws Assembler.AssemblyException {
        Assembler.ParsedLine actual = Assembler.parseLine(source, lineNumber);
        List<String> expectedOperands = Arrays.asList(operands);
        if (!actual.label.equals(label) || !actual.opcode.equals(opcode)
                || !actual.operands.equals(expectedOperands)
                || actual.lineNumber != lineNumber || !actual.sourceText.equals(source)) {
            throw new AssertionError(name + ": expected label=" + label + ", opcode=" + opcode
                    + ", operands=" + expectedOperands + "; actual label=" + actual.label
                    + ", opcode=" + actual.opcode + ", operands=" + actual.operands);
        }
        passed++;
        System.out.println("PASS " + name + ": expected=actual label='" + label
                + "', opcode=" + opcode + ", operands=" + expectedOperands);
    }

    /** Rejection must identify both the source line and the expected problem. */
    private static void expectRejected(String name, String source, int lineNumber, String reason) {
        try {
            Assembler.parseLine(source, lineNumber);
        } catch (Assembler.AssemblyException error) {
            String message = error.getMessage();
            if (!message.startsWith("line " + lineNumber + ": ") || !message.contains(reason)) {
                throw new AssertionError(name + ": expected diagnostic containing '" + reason
                        + "'; actual=" + message);
            }
            passed++;
            System.out.println("PASS " + name + ": expected rejection '" + reason
                    + "'; actual=" + message);
            return;
        }
        throw new AssertionError(name + ": expected rejection, but line was accepted");
    }

    /** Check each line of a reconstructed countdown source, not its encoding yet. */
    private static void checkCountdownFixture() throws Exception {
        List<String> lines = Files.readAllLines(Paths.get("tests", "fixtures", "countdown.as"),
                StandardCharsets.UTF_8);
        String[][] expected = {
            {"", "lw", "0", "1", "five"},
            {"", "lw", "1", "2", "3"},
            {"start", "add", "1", "2", "1"},
            {"", "beq", "0", "1", "done"},
            {"", "beq", "0", "0", "start"},
            {"", "noop"},
            {"done", "halt"},
            {"five", ".fill", "5"},
            {"negone", ".fill", "-1"},
            {"two", ".fill", "2"}
        };
        if (lines.size() != expected.length) {
            throw new AssertionError("countdown: expected 10 source lines; actual=" + lines.size());
        }
        for (int i = 0; i < expected.length; i++) {
            String[] fields = expected[i];
            expectParsed("countdown line " + (i + 1), lines.get(i), i + 1, fields[0], fields[1],
                    Arrays.copyOfRange(fields, 2, fields.length));
        }
    }
}
