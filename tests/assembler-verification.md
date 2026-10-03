# Assembler implementation and verification

## Scope

Java SMC assembler completion on 2026-10-03. Changes are confined to `assembler/`, `tests/`, and essential `README.md` updates. The existing directory layout, default package, parser test source, countdown source, teammate directories, and earlier report notes are preserved. No simulator functionality is implemented here.

## Algorithms

```text
pass 1:
    for every physical source line:
        reject a 65537th word
        parse using the existing column-based parser
        if labeled: reject duplicate; record label -> current word address
        retain the parsed line

pass 2:
    for every retained line at address A:
        validate decimal register indices in 0..7
        resolve decimal operands or look up labels
        for symbolic beq: displacement = label address - (A + 1)
        for lw/sw symbols: offset = absolute label address
        reject I-type offsets outside -32768..32767
        encode the instruction, or emit a full 32-bit .fill value

CLI:
    require input and output arguments
    reject input/output aliases
    complete both passes while output remains untouched
    write signed decimal words only after successful validation
    return normally for exit 0; on error, print stderr and exit 1
```

Keeping the parsed source in memory means a forward reference is looked up only after every label has been collected. Physical lines and word addresses differ by one because blank/comment-only lines are rejected and every accepted line emits one word.

## Java methods and data

| Method or class | Responsibility and review point |
| --- | --- |
| `Assembler()` | Private constructor; operations do not require an assembler instance. |
| `main` | Checks the two arguments, closes input/output with try-with-resources, completes assembly before opening output, and reports errors exclusively to stderr. |
| `rejectInputOverwrite` | Checks normalized paths and existing file identity, protecting source files from direct or aliased output paths. |
| `assemble` | Runs the two passes; returns an `int[]` without printing or writing files. |
| `firstPass` | Reads each line, enforces capacity, reuses `parseLine`, and records labels in a case-sensitive map. `putIfAbsent` detects duplicates even at address zero. |
| `secondPass` | Iterates word addresses and creates exactly one machine-code word per retained line. |
| `encodeWord` | Selects the SMC format and opcode. `.fill` bypasses instruction packing and the 16-bit offset check. |
| `encodeRType` | Validates both source registers and destination; sets only opcode, regA, regB, and low-three-bit destination fields. |
| `encodeIType` | Resolves the operand, applies PC-relative subtraction only to symbolic `beq`, validates the signed offset, then inserts its low 16 bits. |
| `parseRegister` | Requires an entire decimal token in 0..7; does not mask invalid values into valid register indices. Register zero is allowed. |
| `resolveOperand` | Parses a whole decimal token or resolves a syntactically valid label; distinguishes malformed operands from undefined labels. |
| `parseDecimal` | Uses a whole-token decimal regex and `Integer.parseInt`; catches overflow as an assembly diagnostic. |
| `parseLine` | Existing parser: indentation determines whether the first token is a label; required operands are retained and trailing comment text ignored. |
| `validateLabel` | Existing six-character, ASCII letter-first label rule. |
| `operandCount` | Existing mnemonic and operand-count validation. |
| `ParsedProgram` | Private first-pass result containing retained lines and the completed symbol table. |
| `ParsedLine` | Existing immutable parsed fields, physical line number, and original source text. |
| `AssemblyException` | Carries a line-numbered user diagnostic; CLI catches it and exits 1. |

Instruction fields are positioned with `opcode << 22`, `regA << 19`, and `regB << 16`. Bitwise OR combines disjoint fields. R-type destination registers occupy bits 2..0. J-type and O-type instructions leave their unused bits zero. I-type offsets are range-checked before `offset & 0xFFFF`: for example, -1 has low bits `0xFFFF`, preventing its high sign bits from overwriting register/opcode fields.

In the countdown, the branch at address 4 targets `start` at address 2. Its displacement is `2 - (4 + 1) = -3`, whose low 16 bits are 65533. With opcode 4 and both register fields zero, the final word is 16842749. A numeric operand `-3` already represents that displacement and must not undergo a second subtraction.

The test class groups encoding, rejection, capacity, and CLI scenarios. `expectWords`/`assertWords` compare independent literal oracles; `expectRejected` checks line-numbered errors. `noops`/`expectLarge` build long sources and verify every interior word. `parseWords` checks the golden file against the supplied ten literals. `runCli` uses `ProcessBuilder` to invoke the real entry point and captures actual exit/stdout/stderr; `readUtf8` decodes streams. The success/failure helpers verify exact output or preservation before reporting a pass. `temporaryFile`/`outputFor` reserve unique artifacts only in the existing build directory. `cleanupTemporaryFiles` removes recorded files individually, allowing up to ten attempts separated by 50 ms if Windows reports a filesystem lock. `main` prints its final PASS summary only after cleanup succeeds. `CliResult` holds the child JVM result. No assertions depend on `-ea`.

## Assumptions retained or made explicit

- Names are case-sensitive, opcodes lowercase, and a label may have an opcode's spelling because its position determines its role.
- Text after required operands is a trailing comment; extra operand-looking text is therefore ignored. Blank/comment-only lines are rejected.
- Decimal tokens allow an optional sign and leading zeros; hexadecimal and decimal prefixes followed by junk are rejected.
- Source files use UTF-8, with LF or CRLF line endings. A missing final newline is accepted. Empty input assembles to empty output.
- The C helper's 1000-byte buffer is not treated as an ISA/source-size requirement. Java reads complete lines, while enforcing the documented 65,536-word limit.
- Machine-code output uses the platform line separator and a newline after every emitted word. Tests compare exact decimal text including these separators.

These grammar choices are documented assumptions where the supplied specification did not settle them. The SMC bit fields, opcodes, address calculations, register limits, and signed offset limits come from the supplied project specification.

## Countdown oracle

`tests/fixtures/countdown.as` remains the earlier reconstructed source. `tests/fixtures/countdown.expected.mc` contains exactly the instructor's supplied ten machine-code values:

```text
8454151
9043971
655361
16842754
16842749
29360128
25165824
5
-1
2
```

No simulator result is claimed by these assembler tests.

## Commands and observed results

Portable commands, from the repository root:

```powershell
javac -Xlint:all -d build/parser-step assembler/Assembler.java tests/AssemblerParserTest.java tests/AssemblerTest.java
java -cp build/parser-step AssemblerParserTest
java -cp build/parser-step AssemblerTest
java -cp build/parser-step Assembler tests/fixtures/countdown.as build/parser-step/countdown.mc
```

Actual commands executed in this environment, using Java/javac 23.0.2 and approved permissions for generated outputs:

```powershell
& 'C:\Program Files\Java\jdk-23\bin\javac.exe' -Xlint:all -d build/parser-step assembler/Assembler.java tests/AssemblerParserTest.java tests/AssemblerTest.java
java -cp build/parser-step AssemblerParserTest
& 'C:\Program Files\Java\jdk-23\bin\java.exe' -cp build/parser-step AssemblerTest
& 'C:\Program Files\Java\jdk-23\bin\java.exe' -cp build/parser-step Assembler tests/fixtures/countdown.as build/parser-step/countdown.mc
git -c safe.directory=C:/Users/User/ComputerArchitecture-SMC diff --check
rg -n '[ \t]+$' assembler/Assembler.java tests/AssemblerTest.java tests/assembler-verification.md tests/fixtures/countdown.expected.mc README.md
git -c safe.directory=C:/Users/User/ComputerArchitecture-SMC check-ignore -v -- build/parser-step/countdown.mc build/parser-step/Assembler.class
git -c safe.directory=C:/Users/User/ComputerArchitecture-SMC status --short --untracked-files=all
```

| Check | Expected | Observed | Exit code |
| --- | --- | --- | --- |
| Compile, including after the test cleanup fix | No warnings/errors | Successful both times; no compiler diagnostics | 0 |
| Existing parser suite | 36 cases pass | `PASS: 36 parser cases (14 valid, 12 rejected, 10 countdown lines)` | 0 |
| Initial new-suite attempt | All assertions and cleanup succeed | All 66 core and 21 CLI assertions completed, then cleanup failed on a Windows-locked temporary source file | 1 |
| Corrected new-suite rerun | All assertions and cleanup succeed | `PASS: 66 assembler cases; 21 CLI cases`; cleanup succeeded | 0 |
| Separate countdown CLI run | Emit the instructor's ten words without diagnostics | Ten exact words emitted; stdout/stderr empty | 0 |
| Countdown text/byte comparison | Exact words, UTF-8 encoding, native newlines, final newline | All ten lines and expected byte stream matched | 0 |
| Preservation hashes | Earlier parser source, countdown source, ignore rules, report notes, and teammate directory markers unchanged | All six SHA-256 hashes matched their pre-change values | 0 |
| Temporary-artifact check | No `assembler-test-*` files remain | None remain, including artifacts from the initial cleanup failure | 0 |
| `git diff --check` | No whitespace errors | No errors; Git noted its configured README LF-to-CRLF conversion | 0 |
| Explicit whitespace scan of changed text | No trailing spaces/tabs | No matches; ripgrep exit 1 means no matches | 1 |
| Generated-output ignore check | Countdown output and classes excluded | Both matched the existing `/build/` rule | 0 |
| Final Git status | Changes only in authorized paths plus pre-existing changes | Two modified tracked files and nine untracked paths overall; earlier restructuring changes retained | 0 |

The 66 core cases comprise 21 encoding/data cases, 36 rejection cases, and 9 capacity/symbolic-boundary cases. The 21 CLI cases comprise 5 successful assemblies and 16 expected exit-1 scenarios: syntax/semantic errors, argument count, input/output I/O failures, and input/output aliases. All CLI scenarios assert empty stdout; successful ones also assert empty stderr and exact machine-code bytes.

Tests cover every opcode and format, valid register extremes, register-zero operands, forward/backward/self references, `.fill` extremes, absolute versus relative addresses, numeric and symbolic signed-16-bit boundaries, undefined/duplicate/case-mismatched labels, malformed decimals, register validation, signed-32-bit overflow, and 65,536 versus 65,537 source words. A symbolic `.fill` at address 65535 succeeds, while absolute `lw`/`sw` labels above 32767 fail the signed-offset check. A symbolic `beq` to address 32768 succeeds from address zero because its displacement is 32767.

The initial failure was in the test runner's cleanup, not an encoding assertion. Its final success summary was moved after cleanup, and cleanup now has bounded retries. The ten identified leftover files from that attempt were removed individually from the generated-output directory; the successful rerun cleaned its own files. No assembler implementation change was needed to fix that runner failure. There are no remaining test failures.

The machine-code golden fixture supplies exact decimal words; output bytes are checked against those words joined using the platform's newline convention (CRLF on this Windows run). This does not claim that an LF golden fixture and a CRLF output file have identical raw bytes. The generated, verified result remains at `build/parser-step/countdown.mc` for review.

This objective modified `assembler/Assembler.java` and essential `README.md` text, and added `tests/AssemblerTest.java`, `tests/assembler-verification.md`, and `tests/fixtures/countdown.expected.mc`. The `.gitignore` modification and other untracked project files were already present at the beginning of this objective. Hash checks confirm the ignore file and earlier notes/tests/fixtures/directory markers were preserved. No Git commit, push, or merge was performed.
