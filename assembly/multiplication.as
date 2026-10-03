        add 0 0 1        ; Initialize the product to zero
        lw 0 2 mcand     ; Load the multiplicand
        lw 0 3 mplier    ; Load the multiplier
        lw 0 4 one       ; Initialize the bit mask to one
        lw 0 5 bits      ; Check exactly 15 multiplier bits
        lw 0 6 negone    ; Load minus one for the counter
loop    nand 3 4 7       ; Compute NOT(multiplier AND mask)
        nand 7 7 7       ; Invert again to obtain multiplier AND mask
        beq 0 7 skip     ; Skip addition when the current bit is zero
        add 1 2 1        ; Add the current multiplicand to the product
skip    add 2 2 2        ; Double the multiplicand for the next bit
        add 4 4 4        ; Double the mask for the next bit
        add 5 6 5        ; Decrease the remaining bit count
        beq 0 5 done     ; Finish after checking bits 0 through 14
        beq 0 0 loop     ; Continue with the next bit
done    halt             ; Leave the product in register 1
mcand   .fill 32766       ; Default multiplicand
mplier  .fill 10383       ; Default multiplier
one     .fill 1           ; Initial mask
bits    .fill 15          ; Number of bits to check
negone  .fill -1          ; Counter decrement
