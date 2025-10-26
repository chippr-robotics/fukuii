# Ethereum Test Suite

This folder contains a git submodule pointing at the Ethereum Consensus Tests,
also known as the Ethereum Test Suite (ETS), config files for retesteth (the
tool for running these tests) and a wrapper script to set the its command line
options. Oh, and this readme file is in there too, of course.

* ETS: https://github.com/ethereum/tests
* retesteth: https://github.com/ethereum/retesteth

## Running locally

Use the `test-ets.sh` script to boot Fukuii and run retesteth against it.

## Continuous integration

The tests can be run as part of CI using the `test-ets.sh` script. Output is stored as artifacts.

Two test suites are run; GeneralStateTests and BlockchainTests. These seem to
be the only ones maintained and recommended at the moment.

## Running ETS locally

Start Fukuii in test mode:

    sbt -Dconfig.file=./src/main/resources/conf/testmode.conf -Dlogging.logs-level=WARN run

NB. raising the log level is a good idea as there will be a lot of output,
depending on how many tests you run.

Once the RPC API is up, run retesteth (requires retesteth to be installed separately):

    ets/retesteth -t GeneralStateTests

You can also run parts of the suite; refer to `ets/retesteth --help` for details.

## Running retesteth separately

You should run Fukuii outside of any container as that is probably more convenient for your
tooling (eg. attaching a debugger.)

    sbt -Dconfig.file=./src/main/resources/conf/testmode.conf -Dlogging.logs-level=WARN run

Retesteth will need to be able to connect to Fukuii. If running retesteth in a container,
make sure it can access the host system where Fukuii is running.

## Useful options:

You can run one test by selecting one suite and using `--singletest`, for instance:

However it's not always clear in wich subfolder the suite is when looking at the output of retesteth.

To get more insight about what is happening, you can use `--verbosity 6`. It will print every RPC call 
made by retesteth and also print out the state by using our `debug_*` endpoints. Note however that 
`debug_accountRange` and `debug_storageRangeAt` implementations are not complete at the moment :

 - `debug_accountRange` will only list accounts known at the genesis state. 
 - `debug_storageRangeAt` is not able to show the state after an arbitrary transaction inside a block.
It will just return the state after all transaction in the block have run.