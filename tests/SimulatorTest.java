import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Independent instruction tests, exact trace comparisons, and real CLI integration. */
public final class SimulatorTest {
    private static final List<Path> TEMPORARY_FILES = new ArrayList<>();
    private static final Pattern STATE_MARKER = Pattern.compile("(?m)^@@@\r?$");
    private static final Pattern PC_LINE = Pattern.compile("(?m)^\tpc (-?[0-9]+)\r?$");
    private static final int[] COUNTDOWN = {
        8454151, 9043971, 655361, 16842754, 16842749,
        29360128, 25165824, 5, -1, 2
    };
    private static final int[] COUNTDOWN_PCS = {
        0, 1, 2, 3, 4, 2, 3, 4, 2, 3, 4, 2, 3, 4, 2, 3, 6, 7
    };
    private static int corePassed;
    private static int integrationPassed;
    private static int cliPassed;

    private SimulatorTest() {
    }

    public static void main(String[] args) throws Exception {
        try {
            testLoadingAndFormatting();
            testInstructions();
            testRuntimeErrors();
            testCompleteRuns();
            testCli();
        } finally {
            cleanupTemporaryFiles();
        }
        System.out.println("PASS: " + corePassed + " simulator core cases; "
                + integrationPassed + " execution/trace cases; " + cliPassed + " CLI cases");
    }

    private static void testLoadingAndFormatting() throws Exception {
        Simulator.State initial = new Simulator.State();
        if (initial.pc != 0 || initial.numMemory != 0 || initial.reg.length != 8
                || initial.mem.length != 65536) {
            throw new AssertionError("incorrect initial machine state or dimensions");
        }
        for (int value : initial.reg) {
            if (value != 0) {
                throw new AssertionError("register not initially zero");
            }
        }
        for (int value : initial.mem) {
            if (value != 0) {
                throw new AssertionError("memory not initially zero");
            }
        }
        passCore("zero-initialized state and exact dimensions");

        try (Capture capture = new Capture()) {
            Simulator.State loaded = load("-2147483648\r\n +2147483647 \r\n\t-1\t", capture.output);
            if (loaded.pc != 0 || loaded.numMemory != 3 || loaded.mem[0] != Integer.MIN_VALUE
                    || loaded.mem[1] != Integer.MAX_VALUE || loaded.mem[2] != -1
                    || loaded.mem[3] != 0 || loaded.mem[65535] != 0
                    || !Arrays.equals(loaded.reg, new int[8])) {
                throw new AssertionError("signed loading or numMemory incorrect");
            }
            assertText("load echoes", nativeLines("memory[0]=-2147483648\n"
                    + "memory[1]=2147483647\nmemory[2]=-1\n"), capture.text());
        }
        passCore("signed decimal loading, whitespace, CRLF, optional final newline");

        try (Capture capture = new Capture()) {
            Simulator.State empty = load("", capture.output);
            if (empty.numMemory != 0 || !capture.text().isEmpty()) {
                throw new AssertionError("empty loading must load no words and print no echoes");
            }
        }
        passCore("empty loader input");
        for (String token : new String[] {"", "12junk", "0x10", "1.5", "+", "--1"}) {
            expectLoadError("malformed word '" + token + "'", token + "\n", 1, "invalid machine-code word");
        }
        for (String token : new String[] {"2147483648", "-2147483649"}) {
            expectLoadError("word overflow " + token, token, 1, "outside signed 32-bit range");
        }
        expectLoadError("physical line diagnostic", "0\nbad\n", 2, "invalid machine-code word");

        String[] words = new String[65536];
        Arrays.fill(words, "0");
        try (PrintStream sink = sink()) {
            Simulator.State full = load(String.join("\n", words), sink);
            if (full.numMemory != 65536 || full.mem[65535] != 0) {
                throw new AssertionError("full memory loading failed");
            }
        }
        passCore("65536 loaded words accepted");
        expectLoadError("65537 words rejected", String.join("\n", words) + "\n0",
                65537, "exceeds 65536");

        Simulator.State printable = new Simulator.State();
        printable.pc = -3;
        printable.numMemory = 2;
        printable.mem[0] = -1;
        printable.mem[1] = Integer.MAX_VALUE;
        printable.mem[100] = 123; // Outside numMemory: must not be printed.
        printable.reg[1] = -5;
        printable.reg[7] = 42;
        String expected = "\n@@@\nstate:\n\tpc -3\n\tmemory:\n"
                + "\t\tmem[ 0 ] -1\n\t\tmem[ 1 ] 2147483647\n\tregisters:\n"
                + "\t\treg[ 0 ] 0\n\t\treg[ 1 ] -5\n\t\treg[ 2 ] 0\n"
                + "\t\treg[ 3 ] 0\n\t\treg[ 4 ] 0\n\t\treg[ 5 ] 0\n"
                + "\t\treg[ 6 ] 0\n\t\treg[ 7 ] 42\nend state\n";
        try (Capture capture = new Capture()) {
            Simulator.printState(printable, capture.output);
            assertText("literal C printState formatting", nativeLines(expected), capture.text());
        }
        passCore("C printState tabs, spaces, initial blank line, final newline, loaded range");
    }

    /** These literal words test decoding independently of the assembler. */
    private static void testInstructions() throws Exception {
        Simulator.State state = instruction(0, 655363); // add 1 2 3
        state.reg[1] = 20;
        state.reg[2] = -7;
        expectStep("add", state, 1, false, regs(0, 20, -7, 13, 0, 0, 0, 0));

        state = instruction(0, 4849667); // nand 1 2 3
        state.reg[1] = 10;
        state.reg[2] = 12;
        expectStep("nand", state, 1, false, regs(0, 10, 12, -9, 0, 0, 0, 0));
        state = instruction(0, 4849667);
        state.reg[1] = -1;
        expectStep("nand covers every bit", state, 1, false, regs(0, -1, 0, -1, 0, 0, 0, 0));

        state = instruction(0, 8454149); // lw 0 1 5
        state.mem[5] = -17;
        expectStep("lw positive offset", state, 1, false, regs(0, -17, 0, 0, 0, 0, 0, 0), 5, -17);
        state = instruction(0, 12713983); // sw 0 1 -1
        state.reg[0] = 6;
        state.reg[1] = -17;
        expectStep("sw negative offset", state, 1, false, regs(6, -17, 0, 0, 0, 0, 0, 0), 5, -17);

        state = instruction(5, 17498109); // beq 1 2 -3
        state.reg[1] = 5;
        state.reg[2] = 5;
        expectStep("taken backward beq", state, 3, false, regs(0, 5, 5, 0, 0, 0, 0, 0));
        state = instruction(5, 17498109);
        state.reg[1] = 5;
        state.reg[2] = 4;
        expectStep("not-taken beq", state, 6, false, regs(0, 5, 4, 0, 0, 0, 0, 0));
        state = instruction(0, 16809983); // beq 0 0 32767
        expectStep("beq positive signed boundary", state, 32768, false, new int[8]);
        state = instruction(32768, 16809984); // beq 0 0 -32768
        expectStep("beq negative signed boundary", state, 1, false, new int[8]);
        state = instruction(0, 16842751); // beq 0 0 -1
        expectStep("self-loop branch changes no other state", state, 0, false, new int[8]);

        state = instruction(5, 21626880); // jalr 1 2
        state.reg[1] = 9;
        expectStep("jalr different registers", state, 9, false, regs(0, 9, 6, 0, 0, 0, 0, 0));
        state = instruction(5, 21561344); // jalr 1 1
        state.reg[1] = 19;
        expectStep("jalr identical registers", state, 6, false, regs(0, 6, 0, 0, 0, 0, 0, 0));
        state = instruction(5, 20971520); // jalr 0 0
        state.reg[0] = 19;
        expectStep("jalr may write register zero", state, 6, false, regs(6, 0, 0, 0, 0, 0, 0, 0));

        expectStep("halt advances PC", instruction(0, 25165824), 1, true, new int[8]);
        state = instruction(0, 29360128);
        state.reg[7] = -12;
        expectStep("noop preserves registers", state, 1, false, regs(0, 0, 0, 0, 0, 0, 0, -12));

        state = instruction(0, 655363);
        state.reg[1] = Integer.MAX_VALUE;
        state.reg[2] = 1;
        expectStep("add overflow wraps", state, 1, false,
                regs(0, Integer.MAX_VALUE, 1, Integer.MIN_VALUE, 0, 0, 0, 0));
        state = instruction(0, 655363);
        state.reg[1] = Integer.MIN_VALUE;
        state.reg[2] = -1;
        expectStep("add underflow wraps", state, 1, false,
                regs(0, Integer.MIN_VALUE, -1, Integer.MAX_VALUE, 0, 0, 0, 0));
        state = instruction(0, 655361); // add 1 2 1
        state.reg[1] = 3;
        state.reg[2] = 4;
        expectStep("add destination aliases source", state, 1, false, regs(0, 7, 4, 0, 0, 0, 0, 0));
        state = instruction(0, 655360); // add 1 2 0
        state.reg[1] = 3;
        state.reg[2] = 4;
        expectStep("add writes register zero", state, 1, false, regs(7, 3, 4, 0, 0, 0, 0, 0));

        state = instruction(2, 8486912); // lw 0 1 -32768
        state.reg[0] = 32768;
        state.mem[0] = 42;
        expectStep("lw sign extension and address zero", state, 3, false,
                regs(32768, 42, 0, 0, 0, 0, 0, 0), 0, 42);
        state = instruction(0, 8486911); // lw 0 1 32767
        state.mem[32767] = -5;
        expectStep("lw maximum positive offset", state, 1, false, regs(0, -5, 0, 0, 0, 0, 0, 0));
        state = instruction(0, 8454144); // lw 0 1 0
        state.reg[0] = 65535;
        state.mem[65535] = 77;
        expectStep("lw highest valid memory address", state, 1, false, regs(65535, 77, 0, 0, 0, 0, 0, 0));
        state = instruction(0, 12713983);
        state.reg[0] = 65536;
        state.reg[1] = 17;
        expectStep("sw highest valid memory address", state, 1, false,
                regs(65536, 17, 0, 0, 0, 0, 0, 0), 65535, 17);
        state = instruction(2, 12713983);
        state.reg[0] = 1;
        state.reg[1] = 77;
        expectStep("sw address zero", state, 3, false, regs(1, 77, 0, 0, 0, 0, 0, 0), 0, 77);
        state = instruction(0, 12648453); // sw 0 1 5
        state.reg[1] = 12;
        expectStep("sw positive offset", state, 1, false, regs(0, 12, 0, 0, 0, 0, 0, 0), 5, 12);
        state = instruction(0, 8388613); // lw 0 0 5
        state.mem[5] = 9;
        expectStep("lw writes register zero", state, 1, false, regs(9, 0, 0, 0, 0, 0, 0, 0));

        state = instruction(10, 0); // Valid zero-filled memory beyond loaded code.
        state.numMemory = 1;
        expectStep("fetch is bounded by memory, not numMemory", state, 11, false, new int[8]);
    }

    private static void testRuntimeErrors() throws Exception {
        Simulator.State state = instruction(0, 8519679); // lw 0 1 -1
        expectRuntimeError("lw negative address", state, "lw address -1");
        state = instruction(0, 12713983);
        expectRuntimeError("sw negative address", state, "sw address -1");
        state = instruction(0, 8454144);
        state.reg[0] = 65536;
        expectRuntimeError("lw address 65536", state, "lw address 65536");
        state = instruction(0, 12648448); // sw 0 1 0
        state.reg[0] = 65536;
        expectRuntimeError("sw address 65536", state, "sw address 65536");
        state = instruction(0, 8454145); // lw 0 1 1
        state.reg[0] = Integer.MAX_VALUE;
        expectRuntimeError("address addition diagnosed without int wrap", state, "lw address 2147483648");
        state = instruction(0, 12713983);
        state.reg[0] = Integer.MIN_VALUE;
        expectRuntimeError("negative address addition diagnosed without wrap", state, "sw address -2147483649");
        state = new Simulator.State();
        state.pc = -1;
        expectRuntimeError("fetch negative PC", state, "instruction fetch address -1");
        state = new Simulator.State();
        state.pc = 65536;
        expectRuntimeError("fetch PC 65536", state, "instruction fetch address 65536");
        state = instruction(0, 21626880);
        state.reg[1] = -1;
        Simulator.executeOne(state);
        if (state.pc != -1 || state.reg[2] != 1) {
            throw new AssertionError("jalr link/jump should occur before next fetch validation");
        }
        expectRuntimeError("invalid jalr target diagnosed on fetch", state, "instruction fetch address -1");
    }

    private static void testCompleteRuns() throws Exception {
        Run countdown = runWords(COUNTDOWN);
        expectRun("official countdown final state/counts", countdown, 7, 17, 10,
                regs(0, 0, -1, 0, 0, 0, 0, 0), COUNTDOWN_PCS);
        assertBytes("countdown full C-format trace", expectedTrace(), countdown.text.getBytes(StandardCharsets.UTF_8));
        integrationPassed++;
        System.out.println("PASS countdown exact trace: every intermediate state and whitespace byte matched");

        Run mixed = runAssembly("    lw 0 1 a", "    lw 0 2 b", "    add 1 2 3", "    nand 1 2 4",
                "    sw 0 3 out", "    lw 0 5 out", "    beq 3 5 skip", "    noop", "skip noop",
                "    lw 0 6 fn", "    jalr 6 7", "    halt", "target halt", "a .fill 6", "b .fill 3",
                "out .fill 0", "fn .fill target");
        expectRun("all eight instructions assembled and executed", mixed, 13, 11, 17,
                regs(0, 6, 3, 9, -3, 9, 12, 11), new int[] {0, 1, 2, 3, 4, 5, 6, 8, 9, 10, 12, 13},
                15, 9);

        Run same = runAssembly("    lw 0 1 dest", "    jalr 1 1", "    halt", "dest .fill 99");
        expectRun("identical jalr falls through", same, 3, 3, 4, regs(0, 2, 0, 0, 0, 0, 0, 0),
                new int[] {0, 1, 2, 3});
        Run returning = runAssembly("    lw 0 1 ptr", "    jalr 1 7", "    halt", "func noop",
                "    jalr 7 2", "ptr .fill func");
        expectRun("jalr call and return", returning, 3, 5, 6, regs(0, 3, 5, 0, 0, 0, 0, 2),
                new int[] {0, 1, 3, 4, 2, 3});

        Run outside = runAssembly("    lw 0 1 hword", "    sw 0 1 100", "    lw 0 2 dest",
                "    jalr 2 3", "hword .fill 25165824", "dest .fill 100");
        expectRun("store and execute outside loaded range", outside, 101, 5, 6,
                regs(0, 25165824, 100, 4, 0, 0, 0, 0), new int[] {0, 1, 2, 3, 100, 101}, 100, 25165824);
        if (outside.text.contains("mem[ 100 ]")) {
            throw new AssertionError("printState incorrectly expanded numMemory");
        }

        Simulator.State last = instruction(65535, 25165824);
        try (Capture capture = new Capture()) {
            long executed = Simulator.simulate(last, capture.output);
            expectRun("halt at memory boundary needs no next fetch", new Run(last, executed, capture.text()),
                    65536, 1, 0, new int[8], new int[] {65535, 65536});
        }
    }

    /** Redirect child output to files so full traces cannot fill a pipe and deadlock. */
    private static void testCli() throws Exception {
        Path code = temporaryFile(".mc");
        CliResult assembled = runCli("Assembler", "tests/fixtures/countdown.as", code.toString());
        assertExit("countdown assembler CLI", assembled, 0, "");
        if (assembled.stdout.length != 0 || !Arrays.equals(parseWords(code), COUNTDOWN)) {
            throw new AssertionError("assembler CLI pipeline machine words incorrect");
        }
        passCli("assembler countdown pipeline", "exit=0; ten exact words");
        CliResult simulated = runCli("Simulator", code.toString());
        assertExit("countdown simulator CLI", simulated, 0, "");
        assertBytes("countdown CLI exact trace", expectedTrace(), simulated.stdout);
        assertTrace("countdown CLI", new String(simulated.stdout, StandardCharsets.UTF_8), 17, COUNTDOWN_PCS);
        Files.write(Paths.get("build", "parser-step", "countdown.sim.out"), simulated.stdout);
        byte[] rawReference = Files.readAllBytes(Paths.get("tests", "fixtures", "ExSimulator.txt"));
        if (Arrays.equals(rawReference, simulated.stdout)) {
            throw new AssertionError("raw pasted reference unexpectedly lost its known presentation differences");
        }
        System.out.println("RAW REFERENCE DIFFERENCE: ExSimulator.txt=" + rawReference.length
                + " bytes, CLI trace=" + simulated.stdout.length + " bytes, first difference="
                + firstDifference(rawReference, simulated.stdout)
                + "; heading and four extra CRLF sequences are documented separately");
        passCli("simulator countdown pipeline", "exit=0; exact C-format trace; 18 states; final PC=7");

        for (String text : new String[] {"25165824", "  +25165824 \r\n"}) {
            Path source = temporaryFile(".mc");
            Files.write(source, text.getBytes(StandardCharsets.UTF_8));
            CliResult result = runCli("Simulator", source.toString());
            assertExit("single halt", result, 0, "");
            assertTrace("single halt", new String(result.stdout, StandardCharsets.UTF_8), 1, new int[] {0, 1});
            passCli("halt input '" + text.replace("\r", "\\r").replace("\n", "\\n") + "'",
                    "exit=0; two states; one instruction");
        }

        String[][] failures = {
            {"numeric prefix", "12junk", "invalid machine-code word", "0"},
            {"blank input line", "\n", "invalid machine-code word", "0"},
            {"positive overflow", "2147483648", "outside signed 32-bit range", "0"},
            {"negative overflow", "-2147483649", "outside signed 32-bit range", "0"},
            {"lw invalid address", "8519679", "lw address -1", "1"},
            {"sw invalid address", "12713983", "sw address -1", "1"},
            {"negative branch target", "16842750", "instruction fetch address -1", "2"}
        };
        for (String[] test : failures) {
            Path source = temporaryFile(".mc");
            Files.write(source, test[1].getBytes(StandardCharsets.UTF_8));
            CliResult result = runCli("Simulator", source.toString());
            assertExit(test[0], result, 1, test[2]);
            String trace = new String(result.stdout, StandardCharsets.UTF_8);
            if (countMarkers(trace) != Integer.parseInt(test[3]) || trace.contains("machine halted")) {
                throw new AssertionError(test[0] + ": wrong error trace or false halt summary");
            }
            passCli(test[0], "exit=1; stderr=" + result.stderr.trim());
        }
        for (String[] args : new String[][] {{}, {"one", "two"}}) {
            CliResult result = runCli("Simulator", args);
            assertExit("usage", result, 1, "usage:");
            if (result.stdout.length != 0) {
                throw new AssertionError("usage polluted stdout");
            }
            passCli("usage with " + args.length + " arguments", "exit=1; stderr only");
        }
        Path missing = temporaryFile(".mc");
        Files.delete(missing);
        CliResult result = runCli("Simulator", missing.toString());
        assertExit("missing file", result, 1, "error:");
        if (result.stdout.length != 0) {
            throw new AssertionError("missing file produced trace output");
        }
        passCli("missing input", "exit=1; stderr only");
    }

    private static Simulator.State load(String text, PrintStream output) throws Exception {
        try (BufferedReader reader = new BufferedReader(new StringReader(text))) {
            return Simulator.loadMachineCode(reader, output);
        }
    }

    private static PrintStream sink() throws Exception {
        return new PrintStream(new OutputStream() {
            @Override
            public void write(int value) {
                // Discard large loading echoes in capacity-only tests.
            }
        }, false, "UTF-8");
    }

    private static void expectLoadError(String name, String text, int line, String diagnostic) throws Exception {
        try (PrintStream output = sink()) {
            try {
                load(text, output);
            } catch (Simulator.SimulationException error) {
                if (!error.getMessage().startsWith("line " + line + ": ")
                        || !error.getMessage().contains(diagnostic)) {
                    throw new AssertionError(name + ": unexpected diagnostic " + error.getMessage());
                }
                passCore(name + ": expected rejection; actual=" + error.getMessage());
                return;
            }
        }
        throw new AssertionError(name + ": expected loading failure");
    }

    private static Simulator.State instruction(int pc, int word) {
        Simulator.State state = new Simulator.State();
        state.pc = pc;
        state.mem[pc] = word;
        return state;
    }

    private static int[] regs(int... values) {
        if (values.length != 8) {
            throw new AssertionError("test oracle must specify exactly eight registers");
        }
        return values;
    }

    private static void expectStep(String name, Simulator.State state, int expectedPC, boolean expectedHalt,
            int[] expectedRegs, int... memoryChecks) throws Exception {
        int loaded = state.numMemory;
        boolean halted = Simulator.executeOne(state);
        if (state.pc != expectedPC || halted != expectedHalt || state.numMemory != loaded
                || !Arrays.equals(state.reg, expectedRegs)) {
            throw new AssertionError(name + ": expected PC=" + expectedPC + ", halt=" + expectedHalt
                    + ", regs=" + Arrays.toString(expectedRegs) + "; actual PC=" + state.pc
                    + ", halt=" + halted + ", regs=" + Arrays.toString(state.reg));
        }
        assertMemory(name, state, memoryChecks);
        passCore(name + ": PC=" + state.pc + "; registers/memory/numMemory match");
    }

    private static void expectRuntimeError(String name, Simulator.State state, String diagnostic) throws Exception {
        try {
            Simulator.executeOne(state);
        } catch (Simulator.SimulationException error) {
            if (!error.getMessage().contains(diagnostic) || !error.getMessage().contains("outside memory")) {
                throw new AssertionError(name + ": actual=" + error.getMessage());
            }
            passCore(name + ": expected rejection; actual=" + error.getMessage());
            return;
        }
        throw new AssertionError(name + ": expected runtime failure");
    }

    private static Run runWords(int[] words) throws Exception {
        StringBuilder input = new StringBuilder();
        for (int word : words) {
            input.append(word).append('\n');
        }
        try (Capture capture = new Capture()) {
            Simulator.State state = load(input.toString(), capture.output);
            long count = Simulator.simulate(state, capture.output);
            return new Run(state, count, capture.text());
        }
    }

    private static Run runAssembly(String... source) throws Exception {
        try (BufferedReader reader = new BufferedReader(new StringReader(String.join("\n", source)))) {
            return runWords(Assembler.assemble(reader));
        }
    }

    private static void expectRun(String name, Run actual, int pc, long instructions, int numMemory,
            int[] registers, int[] pcs, int... memoryChecks) {
        if (actual.state.pc != pc || actual.executed != instructions || actual.state.numMemory != numMemory
                || !Arrays.equals(registers, actual.state.reg)) {
            throw new AssertionError(name + ": incorrect final state, loaded words, or instruction count");
        }
        assertMemory(name, actual.state, memoryChecks);
        assertTrace(name, actual.text, instructions, pcs);
        integrationPassed++;
        System.out.println("PASS " + name + ": PC=" + pc + ", instructions=" + instructions
                + ", numMemory=" + numMemory + ", states=" + (instructions + 1)
                + ", regs=" + Arrays.toString(registers));
    }

    private static void assertMemory(String name, Simulator.State state, int... pairs) {
        if (pairs.length % 2 != 0) {
            throw new AssertionError("memory oracle needs address/value pairs");
        }
        for (int i = 0; i < pairs.length; i += 2) {
            if (state.mem[pairs[i]] != pairs[i + 1]) {
                throw new AssertionError(name + ": incorrect memory[" + pairs[i] + "]");
            }
        }
    }

    private static void assertTrace(String name, String text, long instructions, int[] expectedPCs) {
        List<Integer> pcs = new ArrayList<>();
        Matcher matcher = PC_LINE.matcher(text);
        while (matcher.find()) {
            pcs.add(Integer.parseInt(matcher.group(1)));
        }
        if (pcs.size() != expectedPCs.length || countMarkers(text) != instructions + 1) {
            throw new AssertionError(name + ": incorrect state/PC count");
        }
        for (int i = 0; i < expectedPCs.length; i++) {
            if (pcs.get(i) != expectedPCs[i]) {
                throw new AssertionError(name + ": PC trace differs at state " + i);
            }
        }
        String summary = nativeLines("machine halted\ntotal of " + instructions
                + " instructions executed\nfinal state of machine:\n\n@@@\n");
        if (text.indexOf(summary) < 0 || text.indexOf("machine halted") != text.lastIndexOf("machine halted")
                || !text.endsWith(nativeLines("end state\n"))) {
            throw new AssertionError(name + ": wrong halt/final-state summary");
        }
    }

    private static int countMarkers(String text) {
        int count = 0;
        Matcher matcher = STATE_MARKER.matcher(text);
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private static String nativeLines(String lfText) {
        return lfText.replace("\r\n", "\n").replace("\n", System.lineSeparator());
    }

    private static byte[] expectedTrace() throws Exception {
        String reference = new String(Files.readAllBytes(
                Paths.get("tests", "fixtures", "countdown.expected.out")), StandardCharsets.UTF_8);
        return nativeLines(reference).getBytes(StandardCharsets.UTF_8);
    }

    private static void assertText(String name, String expected, String actual) {
        assertBytes(name, expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }

    private static void assertBytes(String name, byte[] expected, byte[] actual) {
        if (!Arrays.equals(expected, actual)) {
            throw new AssertionError(name + ": first byte difference=" + firstDifference(expected, actual)
                    + "; expected length=" + expected.length + "; actual length=" + actual.length);
        }
    }

    private static int firstDifference(byte[] expected, byte[] actual) {
        int limit = Math.min(expected.length, actual.length);
        for (int i = 0; i < limit; i++) {
            if (expected[i] != actual[i]) {
                return i;
            }
        }
        return expected.length == actual.length ? -1 : limit;
    }

    private static int[] parseWords(Path path) throws Exception {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        int[] words = new int[lines.size()];
        for (int i = 0; i < words.length; i++) {
            words[i] = Integer.parseInt(lines.get(i));
        }
        return words;
    }

    private static Path temporaryFile(String suffix) throws Exception {
        Path path = Files.createTempFile(Paths.get("build", "parser-step"), "simulator-test-", suffix);
        TEMPORARY_FILES.add(path);
        return path;
    }

    private static CliResult runCli(String className, String... args) throws Exception {
        Path stdout = temporaryFile(".stdout");
        Path stderr = temporaryFile(".stderr");
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        List<String> command = new ArrayList<>();
        command.add(Paths.get(System.getProperty("java.home"), "bin", executable).toString());
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(className);
        command.addAll(Arrays.asList(args));
        Process process = new ProcessBuilder(command).redirectOutput(stdout.toFile())
                .redirectError(stderr.toFile()).start();
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("CLI timed out: " + command);
        }
        System.out.println("RUN " + command + " => exit=" + process.exitValue());
        return new CliResult(process.exitValue(), Files.readAllBytes(stdout),
                new String(Files.readAllBytes(stderr), StandardCharsets.UTF_8));
    }

    private static void assertExit(String name, CliResult actual, int expected, String diagnostic) {
        if (actual.exit != expected || (expected == 0 && !actual.stderr.isEmpty())
                || (expected != 0 && !actual.stderr.contains(diagnostic))) {
            throw new AssertionError(name + ": expected exit=" + expected + ", diagnostic='" + diagnostic
                    + "'; actual exit=" + actual.exit + ", stderr=" + actual.stderr);
        }
    }

    private static void cleanupTemporaryFiles() throws Exception {
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

    private static void passCore(String name) {
        corePassed++;
        System.out.println("PASS " + name);
    }

    private static void passCli(String name, String result) {
        cliPassed++;
        System.out.println("PASS CLI " + name + ": " + result);
    }

    private static final class Capture implements AutoCloseable {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final PrintStream output;

        private Capture() throws Exception {
            output = new PrintStream(bytes, false, "UTF-8");
        }

        private String text() {
            output.flush();
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }

        @Override
        public void close() {
            output.close();
        }
    }

    private static final class Run {
        final Simulator.State state;
        final long executed;
        final String text;

        private Run(Simulator.State state, long executed, String text) {
            this.state = state;
            this.executed = executed;
            this.text = text;
        }
    }

    private static final class CliResult {
        final int exit;
        final byte[] stdout;
        final String stderr;

        private CliResult(int exit, byte[] stdout, String stderr) {
            this.exit = exit;
            this.stdout = stdout;
            this.stderr = stderr;
        }
    }
}
