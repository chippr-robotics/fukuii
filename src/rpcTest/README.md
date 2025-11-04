# Instruction to semi-automatic json-rpc testing

General Note:
There are 3 types of tests in current test suite which requires 3 different configurations of fukuii:
MainNet, PrivNet, PrivNetNoMining. Different tests types are marked by different scala test tags.
Correct configurations for private net is provided in `fukuii/src/rpcTest/resources/privateNetConfig/conf`
It includes custom genesis block which specifies 3 different pre-funded accounts needed for transaction tests.
Private keys for pre-funded accounts are located in `fukuii/src/rpcTest/resources/privateNetConfig/keystore`.

1. Build `fukuii` client via `sbt dist`.
2. Unzip built client to some directory i.e `~/fukuii_build`
3. Run script `patch-fukuii` (it's in resources dir) with path to your fukuii instance. Example invocation assuming that fukuii is in `~/fukuii_build/fukuii-X.Y.Z` looks as follows:
    
        ./resources/patch-fukuii ~/fukuii_build/fukuii-3.2.1

4. Go to `~/fukuii_build/fukuii-3.2.1` directory and run fukuii on ETC mainnet with command:

        ./bin/fukuii-launcher etc -Dfukuii.sync.do-fast-sync=false -Dfukuii.network.discovery.discovery-enabled=true -Dfukuii.network.rpc.http.mode=http
        
5. Ensure it has at least `150000` blocks.
6. Go to `fukuii` source dir and run 

        sbt "RpcTest / testOnly -- -n MainNet"
        
7. Turn off Fukuii client in `~/fukuii_build/fukuii-3.2.1`
8. Go to `~/fukuii_build/fukuii-3.2.1` directory and run fukuii using command below (fukuii will be run with miner so you need to wait till DAG is loaded):

        ./bin/fukuii-launcher -Dfukuii.mining.mining-enabled=true
9. Go to `fukuii` source dir and run 

        sbt "RpcTest / testOnly -- -n PrivNet"
        
10. Turn off Fukuii client
11. Go to `~/fukuii_build/fukuii-3.2.1` directory and run Fukuii with mining disabled using command

        ./bin/fukuii-launcher
        
12. Go to `fukuii` source dir and run 

        sbt "RpcTest / testOnly -- -n PrivNetNoMining"
        
13. Turn off Fukuii client.


__TODO__: It seems that simple bash script should be able to run all these tests now.
