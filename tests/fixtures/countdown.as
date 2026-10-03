        lw 0 1 five
        lw 1 2 3
start   add 1 2 1
        beq 0 1 done
        beq 0 0 start
        noop
done    halt
five    .fill 5
negone  .fill -1
two     .fill 2
