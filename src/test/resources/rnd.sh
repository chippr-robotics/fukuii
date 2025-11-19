# Simple script to randomly kill running fukuii instance
# Just copy it to unzipped fukuii cli dir and call ./rnd.sh

signal=KILL

sleep_a_while () {
    sleep $[ ( $RANDOM % 600 ) + 120 ]s
}

sleep_some () {
    sleep $[ ( 120 ) ]s
}

while true; do
    # Note: command launched in background:
    bin/fukuii & 

    # Save PID of command just launched:
    last_pid=$!

    # Sleep for a while:
    sleep_a_while

    # See if the command is still running, and kill it and sleep more if it is:
    if jps| grep 'fukuii'; then
        kill -$signal $last_pid 2> /dev/null
        sleep_some
    fi

    # Go back to the beginning and launch the command again
done
