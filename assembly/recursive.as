        lw      0 1 n          ; Load argument n
        lw      0 2 r          ; Load argument r
        lw      0 6 fn         ; Load the function address
        jalr    6 7            ; Call combination; r7 holds the return address
        halt                  ; Leave C(n,r) in r3
comb    beq     2 0 base       ; C(n,0) = 1
        beq     1 2 base       ; C(n,n) = 1
        lw      0 6 one
        sw      5 7 stack      ; Push the caller's return address
        add     5 6 5
        sw      5 1 stack      ; Push original n
        add     5 6 5
        sw      5 2 stack      ; Push original r
        add     5 6 5
        lw      0 6 neg1
        add     1 6 1          ; n = n-1
        lw      0 6 fn
        jalr    6 7            ; First recursive call: C(n-1,r)
        lw      0 6 one
        sw      5 3 stack      ; Save the first result before the second call
        add     5 6 5
        lw      0 6 neg1
        add     2 6 2          ; r = r-1; restored n is already n-1
        lw      0 6 fn
        jalr    6 7            ; Second recursive call: C(n-1,r-1)
        lw      0 6 neg1
        add     5 6 5
        lw      5 4 stack      ; Pop the first result into r4
        add     4 3 3          ; Return the sum of both results in r3
        add     5 6 5
        lw      5 2 stack      ; Restore original r
        add     5 6 5
        lw      5 1 stack      ; Restore original n
        add     5 6 5
        lw      5 7 stack      ; Restore the caller's return address
        jalr    7 6            ; Return; r4 and r6 are scratch registers
base    lw      0 3 one        ; Return 1 for either base case
        jalr    7 6
n       .fill   7
r       .fill   3
one     .fill   1
neg1    .fill   -1
fn      .fill   comb
stack   .fill   0              ; Stack grows toward higher addresses
