        lw      0       1       n
        lw      0       2       neg1
loop    beq     1       0       done
        add     3       1       3
        add     1       2       1
        beq     0       0       loop
done    sw      0       3       ans
        halt
n       .fill   5
neg1    .fill   -1
ans     .fill   0
