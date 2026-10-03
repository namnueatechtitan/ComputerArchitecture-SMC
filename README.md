# ComputerArchitecture-SMC

Java implementation of the SMC assembler and simulator for CPE 261304, Computer Architecture Project 1.

## Repository layout

```text
ComputerArchitecture-SMC/
├── assembler/
│   └── Assembler.java
├── simulator/
│   ├── Simulator.java
│   └── .gitkeep
├── assembly/
│   └── .gitkeep
├── tests/
│   ├── AssemblerParserTest.java
│   ├── AssemblerTest.java
│   ├── SimulatorTest.java
│   ├── assembler-verification.md
│   ├── simulator-verification.md
│   └── fixtures/
│       ├── CodeFragment.txt
│       ├── ExSimulator.txt
│       ├── countdown.as
│       ├── countdown.expected.mc
│       └── countdown.expected.out
├── report/
│   └── implementation-notes.md
├── .gitignore
└── README.md
```

`simulator/Simulator.java` implements the SMC instruction-level simulator. `assembly/` is reserved for the team's real `multiplication.as`, `combination.as`, and `extra_program.as`; no placeholder programs are provided. `report/` holds implementation notes and will hold the final PDF report.

`tests/fixtures/countdown.as` is a reconstructed countdown test fixture, not a team submission. The original parser tests remain under `tests/`.

## Compile and test the assembler

Run from the repository root with a JDK installed:

```powershell
javac -Xlint:all -d build/parser-step assembler/Assembler.java tests/AssemblerParserTest.java tests/AssemblerTest.java
java -cp build/parser-step AssemblerParserTest
java -cp build/parser-step AssemblerTest
```

Successful suite summaries:

```text
PASS: 36 parser cases (14 valid, 12 rejected, 10 countdown lines)
PASS: 66 assembler cases; 21 CLI cases
```

`AssemblerTest` checks encoding, symbols, numeric limits, errors, program capacity, and actual CLI exit codes. Its temporary files are created in the existing generated-output directory and removed when it finishes. The countdown oracle contains the instructor's exact ten expected words.

The Java sources use the default package. Compiling them together with `-d` places their class files in the same output directory; execute classes without a directory or package prefix. Fixture paths are relative to the repository root.

`build/` is generated output and is ignored by Git. The existing `*.class` rule also excludes compiled classes outside that directory. No Maven, Gradle, or external test framework is needed.

## Assemble a program

After compilation, run from the repository root:

```powershell
java -cp build/parser-step Assembler tests/fixtures/countdown.as build/parser-step/countdown.mc
```

The CLI takes an assembly input path and a machine-code output path. Success exits 0 and writes one signed decimal word per line. Errors exit 1 with diagnostics on stderr. Both assembly passes finish before the output file is opened; input/output aliases are rejected to protect source files.

The two-pass assembler preserves the column-based parser: labels start in column 1; unlabeled instructions start with a space or tab. Labels are case-sensitive, use at most six ASCII letters/digits, and start with a letter. Trailing text after required operands is treated as a comment. Blank/comment-only lines are rejected. Decimal tokens allow an optional `+` or `-` and must fit a signed 32-bit integer; instruction offsets also must fit signed 16 bits.

Documented file-reading assumptions: UTF-8 input, LF or CRLF line endings, a final newline is optional, and empty input produces an empty output. Java does not inherit the C helper's fixed buffer size; the 65,536-word program limit is enforced.

## Current implementation status

The assembler implements both passes, all eight SMC instructions and `.fill`, symbol resolution, numeric/register/offset validation, the program-length limit, and the CLI. See [assembler verification](tests/assembler-verification.md) for method explanations, pseudocode, exact commands, and observed results.

The simulator uses eight integer registers, 65,536 zero-initialized memory words, and all eight instructions. It prints loaded words, the state before each instruction, halt statistics, and one final state using the C reference's tabs and spacing. Register zero remains writable, and `jalr` with identical registers falls through to the newly stored return address.

## Compile, test, and run both tools

Run from the repository root:

```powershell
javac -Xlint:all -d build/parser-step assembler/Assembler.java simulator/Simulator.java tests/AssemblerParserTest.java tests/AssemblerTest.java tests/SimulatorTest.java
java -cp build/parser-step AssemblerParserTest
java -cp build/parser-step AssemblerTest
java -cp build/parser-step SimulatorTest
java -cp build/parser-step Assembler tests/fixtures/countdown.as build/parser-step/countdown.mc
java -cp build/parser-step Simulator build/parser-step/countdown.mc
```

The simulator takes one machine-code input path. Success exits 0; loading and execution errors exit 1 with diagnostics on stderr. File-reading assumptions are UTF-8, whole signed decimal words, optional surrounding whitespace, LF or CRLF, and an optional final newline. Runtime addresses must be within 0 through 65,535. `sw` does not change `numMemory`.

Verified with JDK 23 on Windows: compilation exited 0 without warnings; all 36 parser, 66 assembler, 21 assembler CLI, 51 simulator core, 7 execution/trace, and 14 simulator CLI cases passed. Countdown ended at PC 7, register 1 = 0, register 2 = -1, with 17 executed instructions and 18 state prints. `SimulatorTest` records its actual CLI trace in the ignored `build/parser-step/countdown.sim.out`.

The untouched pasted `ExSimulator.txt` includes a heading and extra blank lines outside the C output. Its 6,676 raw bytes differ from the 6,640-byte CLI trace. The trace exactly matches `countdown.expected.out`, derived from the instructor example before execution by removing only that heading and four extra CRLF sequences. See [simulator verification](tests/simulator-verification.md) for provenance, exact comparisons, commands, exit codes, and method explanations.

## Remaining project work

The team assembly programs and final PDF report are still pending. [Implementation notes](report/implementation-notes.md) preserve the earlier parser and restructuring history; current assembler and simulator status is recorded in their verification documents.
