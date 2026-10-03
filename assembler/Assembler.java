import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Two-pass assembler for the instructor's SMC architecture.
 * The first pass records addresses; the second resolves and encodes operands.
 */
public final class Assembler {
    // The reference parser separates fields using spaces and tabs.
    private static final Pattern TOKEN = Pattern.compile("[^ \\t]+");
    private static final Pattern LABEL = Pattern.compile("[A-Za-z][A-Za-z0-9]{0,5}");
    private static final Pattern DECIMAL = Pattern.compile("[+-]?[0-9]+");
    private static final int MEMORY_WORDS = 65536;
    private static final int OPCODE_SHIFT = 22;
    private static final int REG_A_SHIFT = 19;
    private static final int REG_B_SHIFT = 16;
    private static final int OFFSET_MASK = 0xFFFF;

    private Assembler() {
        // This class contains assembler operations, not per-instance state.
    }

    /** Assemble input into a separate signed-decimal machine-code file. */
    public static void main(String[] args) {
        if (args.length != 2) {
            System.err.println("error: usage: java Assembler <assembly-code-file> <machine-code-file>");
            System.exit(1);
            return;
        }

        try {
            Path input = Paths.get(args[0]);
            Path output = Paths.get(args[1]);
            rejectInputOverwrite(input, output);

            int[] words;
            try (BufferedReader reader = Files.newBufferedReader(input, StandardCharsets.UTF_8)) {
                words = assemble(reader);
            }

            // Open output only after both passes succeed. Assembly errors must
            // not truncate a pre-existing machine-code file.
            try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
                for (int word : words) {
                    writer.write(Integer.toString(word));
                    writer.newLine();
                }
            }
            // Normal return from main gives exit code 0. No stdout diagnostics.
        } catch (AssemblyException | IOException | InvalidPathException error) {
            System.err.println("error: " + error.getMessage());
            System.exit(1);
        }
    }

    /** Protect source files even when output names an alias of the input. */
    private static void rejectInputOverwrite(Path input, Path output) throws IOException {
        if (input.toAbsolutePath().normalize().equals(output.toAbsolutePath().normalize())
                || (Files.exists(output) && Files.isSameFile(input, output))) {
            throw new IOException("input and output must be different files");
        }
    }

    /** Cache parsed source once, then encode it with the complete symbol table. */
    static int[] assemble(BufferedReader reader) throws IOException, AssemblyException {
        ParsedProgram program = firstPass(reader);
        return secondPass(program);
    }

    /** Assign one word address per line and reject duplicate label definitions. */
    private static ParsedProgram firstPass(BufferedReader reader)
            throws IOException, AssemblyException {
        List<ParsedLine> lines = new ArrayList<>();
        Map<String, Integer> symbols = new LinkedHashMap<>();
        String text;
        while ((text = reader.readLine()) != null) {
            int address = lines.size();
            int lineNumber = address + 1;
            if (address == MEMORY_WORDS) {
                throw new AssemblyException(lineNumber, "program exceeds 65536 memory words");
            }
            ParsedLine line = parseLine(text, lineNumber);
            if (!line.label.isEmpty()) {
                Integer previous = symbols.putIfAbsent(line.label, address);
                if (previous != null) {
                    throw new AssemblyException(lineNumber, "duplicate label '" + line.label
                            + "' (first defined at address " + previous + ")");
                }
            }
            lines.add(line);
        }
        return new ParsedProgram(lines, symbols);
    }

    /** Resolve all symbols, including forward references, before returning words. */
    private static int[] secondPass(ParsedProgram program) throws AssemblyException {
        int[] words = new int[program.lines.size()];
        for (int address = 0; address < words.length; address++) {
            words[address] = encodeWord(program.lines.get(address), address, program.symbols);
        }
        return words;
    }

    /** Select the exact SMC opcode and instruction format; .fill emits raw data. */
    private static int encodeWord(ParsedLine line, int address, Map<String, Integer> symbols)
            throws AssemblyException {
        switch (line.opcode) {
            case "add":
                return encodeRType(0, line);
            case "nand":
                return encodeRType(1, line);
            case "lw":
                return encodeIType(2, line, address, symbols, false);
            case "sw":
                return encodeIType(3, line, address, symbols, false);
            case "beq":
                return encodeIType(4, line, address, symbols, true);
            case "jalr": {
                int regA = parseRegister(line.operands.get(0), line.lineNumber);
                int regB = parseRegister(line.operands.get(1), line.lineNumber);
                return (5 << OPCODE_SHIFT) | (regA << REG_A_SHIFT) | (regB << REG_B_SHIFT);
            }
            case "halt":
                return 6 << OPCODE_SHIFT;
            case "noop":
                return 7 << OPCODE_SHIFT;
            case ".fill":
                return resolveOperand(line.operands.get(0), line.lineNumber, symbols);
            default:
                throw new AssemblyException(line.lineNumber, "invalid opcode '" + line.opcode + "'");
        }
    }

    /** Leave every unused R-type bit zero, including bits 15 through 3. */
    private static int encodeRType(int opcode, ParsedLine line) throws AssemblyException {
        int regA = parseRegister(line.operands.get(0), line.lineNumber);
        int regB = parseRegister(line.operands.get(1), line.lineNumber);
        int destination = parseRegister(line.operands.get(2), line.lineNumber);
        return (opcode << OPCODE_SHIFT) | (regA << REG_A_SHIFT)
                | (regB << REG_B_SHIFT) | destination;
    }

    /** Only symbolic beq operands are relative; numeric offsets are already offsets. */
    private static int encodeIType(int opcode, ParsedLine line, int address,
            Map<String, Integer> symbols, boolean pcRelative) throws AssemblyException {
        int regA = parseRegister(line.operands.get(0), line.lineNumber);
        int regB = parseRegister(line.operands.get(1), line.lineNumber);
        String token = line.operands.get(2);
        int offset = resolveOperand(token, line.lineNumber, symbols);
        if (pcRelative && !DECIMAL.matcher(token).matches()) {
            offset -= address + 1;
        }
        if (offset < -32768 || offset > 32767) {
            throw new AssemblyException(line.lineNumber, "offset " + offset
                    + " outside signed 16-bit range [-32768, 32767]");
        }

        // Validate before masking: -1 becomes 0xFFFF, not a negative full word.
        return (opcode << OPCODE_SHIFT) | (regA << REG_A_SHIFT)
                | (regB << REG_B_SHIFT) | (offset & OFFSET_MASK);
    }

    /** Registers are decimal indices, never label references or masked aliases. */
    private static int parseRegister(String token, int lineNumber) throws AssemblyException {
        int register = parseDecimal(token, lineNumber, "register");
        if (register < 0 || register > 7) {
            throw new AssemblyException(lineNumber, "register " + register + " outside range [0, 7]");
        }
        return register;
    }

    /** Distinguish whole decimal tokens from well-formed symbolic references. */
    private static int resolveOperand(String token, int lineNumber, Map<String, Integer> symbols)
            throws AssemblyException {
        if (DECIMAL.matcher(token).matches()) {
            return parseDecimal(token, lineNumber, "operand");
        }
        if (!LABEL.matcher(token).matches()) {
            throw new AssemblyException(lineNumber, "invalid operand '" + token
                    + "': expected decimal integer or label");
        }
        Integer address = symbols.get(token);
        if (address == null) {
            throw new AssemblyException(lineNumber, "undefined label '" + token + "'");
        }
        return address;
    }

    /** Reject numeric prefixes and overflow rather than silently wrapping them. */
    private static int parseDecimal(String token, int lineNumber, String role)
            throws AssemblyException {
        if (!DECIMAL.matcher(token).matches()) {
            throw new AssemblyException(lineNumber, "invalid " + role + " '" + token
                    + "': expected decimal integer");
        }
        try {
            return Integer.parseInt(token);
        } catch (NumberFormatException error) {
            throw new AssemblyException(lineNumber, role + " '" + token
                    + "' outside signed 32-bit range");
        }
    }

    /** The complete first-pass result remains private to the assembly operation. */
    private static final class ParsedProgram {
        final List<ParsedLine> lines;
        final Map<String, Integer> symbols;

        private ParsedProgram(List<ParsedLine> lines, Map<String, Integer> symbols) {
            this.lines = lines;
            this.symbols = symbols;
        }
    }

    /**
     * Parse a physical source line without its newline terminator.
     * Inspect indentation before tokenizing: trimming first would lose the
     * instructor's distinction between a label and an unlabeled instruction.
     *
     * Assumptions: names are case-sensitive, every line produces one word,
     * and text after an opcode's required operands is a trailing comment.
     */
    static ParsedLine parseLine(String text, int lineNumber) throws AssemblyException {
        List<String> tokens = new ArrayList<>();
        Matcher matcher = TOKEN.matcher(text);
        while (matcher.find()) {
            tokens.add(matcher.group());
        }

        if (tokens.isEmpty()) {
            throw new AssemblyException(lineNumber, "missing opcode: blank lines are not supported");
        }

        boolean hasLabel = text.charAt(0) != ' ' && text.charAt(0) != '\t';
        int cursor = 0;
        String label = "";
        if (hasLabel) {
            label = tokens.get(cursor++);
            validateLabel(label, lineNumber);
        }

        if (cursor == tokens.size()) {
            throw new AssemblyException(lineNumber, "missing opcode after label '" + label + "'");
        }

        String opcode = tokens.get(cursor++);
        int required = operandCount(opcode, lineNumber);
        int available = tokens.size() - cursor;
        if (available < required) {
            throw new AssemblyException(lineNumber, "opcode '" + opcode + "' needs "
                    + required + " operands; found " + available);
        }

        // Keep only operands; trailing comment tokens do not occupy memory.
        List<String> operands = tokens.subList(cursor, cursor + required);
        return new ParsedLine(lineNumber, text, label, opcode, operands);
    }

    /** Validate the instructor's six-character, letter-first label rule. */
    private static void validateLabel(String label, int lineNumber) throws AssemblyException {
        if (!LABEL.matcher(label).matches()) {
            throw new AssemblyException(lineNumber, "invalid label '" + label
                    + "': use 1-6 letters/digits, starting with a letter");
        }
    }

    /** Recognize SMC mnemonics and specify how many operands belong to each. */
    private static int operandCount(String opcode, int lineNumber) throws AssemblyException {
        switch (opcode) {
            case "add":
            case "nand":
            case "lw":
            case "sw":
            case "beq":
                return 3;
            case "jalr":
                return 2;
            case "halt":
            case "noop":
                return 0;
            case ".fill":
                return 1;
            default:
                throw new AssemblyException(lineNumber, "invalid opcode '" + opcode + "'");
        }
    }

    /** Immutable parsed fields, with original source retained for diagnostics. */
    static final class ParsedLine {
        final int lineNumber;
        final String sourceText;
        final String label;
        final String opcode;
        final List<String> operands;

        private ParsedLine(int lineNumber, String sourceText, String label,
                String opcode, List<String> operands) {
            this.lineNumber = lineNumber;
            this.sourceText = sourceText;
            this.label = label;
            this.opcode = opcode;
            this.operands = Collections.unmodifiableList(new ArrayList<>(operands));
        }
    }

    /** An assembly diagnostic; the CLI prints it to stderr and exits 1. */
    static final class AssemblyException extends Exception {
        private static final long serialVersionUID = 1L;

        private AssemblyException(int lineNumber, String reason) {
            super("line " + lineNumber + ": " + reason);
        }
    }
}
