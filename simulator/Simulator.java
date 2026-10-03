import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Paths;
import java.util.regex.Pattern;

/** Instruction-level behavioral simulator for the instructor's SMC machine. */
public final class Simulator {
    private static final int NUMMEMORY = 65536;
    private static final int NUMREGS = 8;
    private static final Pattern DECIMAL = Pattern.compile("[+-]?[0-9]+");

    private Simulator() {
    }

    /** Load and execute one machine-code file; errors go to stderr, exit 1. */
    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("error: usage: java Simulator <machine-code-file>");
            System.exit(1);
            return;
        }
        try {
            State state;
            try (BufferedReader reader = Files.newBufferedReader(
                    Paths.get(args[0]), StandardCharsets.UTF_8)) {
                state = loadMachineCode(reader, System.out);
            }
            simulate(state, System.out);
        } catch (IOException | InvalidPathException | SimulationException error) {
            System.err.println("error: " + error.getMessage());
            System.exit(1);
        }
    }

    /** New Java arrays and integer fields start at zero, before any word is loaded. */
    static State loadMachineCode(BufferedReader reader, PrintStream output)
            throws IOException, SimulationException {
        State state = new State();
        String text;
        while ((text = reader.readLine()) != null) {
            int lineNumber = state.numMemory + 1;
            if (state.numMemory == NUMMEMORY) {
                throw new SimulationException("line " + lineNumber
                        + ": machine code exceeds 65536 memory words");
            }
            String token = text.trim();
            if (!DECIMAL.matcher(token).matches()) {
                throw new SimulationException("line " + lineNumber
                        + ": invalid machine-code word '" + token + "'");
            }
            int word;
            try {
                word = Integer.parseInt(token);
            } catch (NumberFormatException error) {
                throw new SimulationException("line " + lineNumber
                        + ": machine-code word outside signed 32-bit range");
            }
            state.mem[state.numMemory] = word;
            output.println("memory[" + state.numMemory + "]=" + word);
            state.numMemory++;
        }
        return state;
    }

    /** One pre-instruction state per execution, plus one final state after halt. */
    static long simulate(State state, PrintStream output) throws SimulationException {
        long executed = 0;
        while (true) {
            printState(state, output);
            boolean halted = executeOne(state);
            executed++; // Includes halt, but never a failed fetch/load/store.
            if (halted) {
                output.println("machine halted");
                output.println("total of " + executed + " instructions executed");
                output.println("final state of machine:");
                printState(state, output);
                return executed;
            }
        }
    }

    /** Fetch/decode/execute using oldPC, then commit the next PC exactly once. */
    static boolean executeOne(State state) throws SimulationException {
        int oldPC = state.pc;
        int word = state.mem[checkedAddress(oldPC, oldPC, "instruction fetch")];
        int opcode = (word >>> 22) & 7;
        int regA = (word >>> 19) & 7;
        int regB = (word >>> 16) & 7;
        int destination = word & 7;
        int offset = (int) (short) (word & 0xFFFF);
        int nextPC = oldPC + 1;
        boolean halted = false;

        switch (opcode) {
            case 0:
                state.reg[destination] = state.reg[regA] + state.reg[regB];
                break;
            case 1:
                state.reg[destination] = ~(state.reg[regA] & state.reg[regB]);
                break;
            case 2: {
                int address = checkedAddress((long) state.reg[regA] + offset, oldPC, "lw");
                state.reg[regB] = state.mem[address];
                break;
            }
            case 3: {
                int address = checkedAddress((long) state.reg[regA] + offset, oldPC, "sw");
                state.mem[address] = state.reg[regB];
                break;
            }
            case 4:
                if (state.reg[regA] == state.reg[regB]) {
                    nextPC = oldPC + 1 + offset;
                }
                break;
            case 5: {
                int originalTarget = state.reg[regA];
                state.reg[regB] = oldPC + 1;
                // Identical registers jump to the newly written return address.
                nextPC = regA == regB ? oldPC + 1 : originalTarget;
                break;
            }
            case 6:
                halted = true;
                break;
            case 7:
                break;
            default:
                throw new SimulationException("at pc " + oldPC + ": unsupported opcode " + opcode);
        }
        state.pc = nextPC;
        // Register zero is a programming convention, not a hardware constraint.
        return halted;
    }

    /** Widen address addition only for validation; register arithmetic remains int. */
    private static int checkedAddress(long address, int pc, String operation)
            throws SimulationException {
        if (address < 0 || address >= NUMMEMORY) {
            throw new SimulationException("at pc " + pc + ": " + operation + " address "
                    + address + " outside memory [0, 65535]");
        }
        return (int) address;
    }

    /** Literal Java port of the C printState text, using native newline sequences. */
    static void printState(State state, PrintStream output) {
        output.println();
        output.println("@@@");
        output.println("state:");
        output.println("\tpc " + state.pc);
        output.println("\tmemory:");
        for (int i = 0; i < state.numMemory; i++) {
            output.println("\t\tmem[ " + i + " ] " + state.mem[i]);
        }
        output.println("\tregisters:");
        for (int i = 0; i < NUMREGS; i++) {
            output.println("\t\treg[ " + i + " ] " + state.reg[i]);
        }
        output.println("end state");
    }

    /** Same four fields and dimensions as the instructor's C stateStruct. */
    static final class State {
        int pc;
        final int[] mem = new int[NUMMEMORY];
        final int[] reg = new int[NUMREGS];
        int numMemory;
    }

    /** Understandable loading/runtime diagnostic; never added to stdout trace. */
    static final class SimulationException extends Exception {
        private static final long serialVersionUID = 1L;

        private SimulationException(String reason) {
            super(reason);
        }
    }
}
