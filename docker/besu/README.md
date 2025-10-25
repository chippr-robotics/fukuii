## How to run Besu

**Note**: This setup uses the **official Hyperledger Besu Docker image** from Docker Hub (`hyperledger/besu`). Chippr Robotics does not build or maintain the Besu image - we only provide this docker-compose configuration for testing and interoperability purposes.

In `runBesu.sh` set the Besu `VERSION` you wish to test and then just run the script in a terminal 

```./runBesu.sh```

When the script is running Prometheus metrics and Grafana will be available at:

`http://localhost:9091` for the list of all available metrics

`http://localhost:3000/login` to access Grafana (login is admin / admin)


### Metrics
Some metrics are already being displayed in Grafana, using part of the dashboard that can be found in `https://grafana.com/grafana/dashboards/10273` and also replicating some metrics being used by the `mantis-ops` grafana dashboard 


### JSON RPC API
JSON-RPC service is available at port 8545

### Official Besu Documentation
For more information about Hyperledger Besu, visit:
- Docker Hub: https://hub.docker.com/r/hyperledger/besu
- Documentation: https://besu.hyperledger.org/
