#!/usr/bin/env bash

# Barad-dûr (Kong API Gateway) Setup Script for Fukuii
# Named after Sauron's Dark Tower - the fortified gateway to Fukuii
# This script helps initialize and configure the Kong API Gateway

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Script directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}Barad-dûr (Kong API Gateway) Setup${NC}"
echo -e "${GREEN}Fortified Gateway to Fukuii${NC}"
echo -e "${GREEN}========================================${NC}"
echo ""

# Check prerequisites
echo -e "${YELLOW}Checking prerequisites...${NC}"

if ! command -v docker &> /dev/null; then
    echo -e "${RED}Error: Docker is not installed${NC}"
    exit 1
fi

if ! command -v docker-compose &> /dev/null; then
    if ! docker compose version &> /dev/null; then
        echo -e "${RED}Error: Docker Compose is not installed${NC}"
        exit 1
    fi
fi

echo -e "${GREEN}✓ Docker is installed${NC}"
echo -e "${GREEN}✓ Docker Compose is installed${NC}"
echo ""

# Check if .env exists, if not copy from .env.example
if [ ! -f "$SCRIPT_DIR/.env" ]; then
    echo -e "${YELLOW}Creating .env file from .env.example...${NC}"
    cp "$SCRIPT_DIR/.env.example" "$SCRIPT_DIR/.env"
    echo -e "${GREEN}✓ .env file created${NC}"
    echo -e "${YELLOW}⚠ Please edit .env file and update passwords and secrets!${NC}"
    echo ""
else
    echo -e "${GREEN}✓ .env file exists${NC}"
    echo ""
fi

# Create required directories (using data directory bindings from .env)
echo -e "${YELLOW}Creating required directories...${NC}"
mkdir -p "$SCRIPT_DIR/fukuii-conf"
mkdir -p "$SCRIPT_DIR/prometheus"
mkdir -p "$SCRIPT_DIR/grafana/provisioning/datasources"
mkdir -p "$SCRIPT_DIR/grafana/provisioning/dashboards"
mkdir -p "$SCRIPT_DIR/grafana/dashboards"
mkdir -p "$SCRIPT_DIR/data/fukuii"
mkdir -p "$SCRIPT_DIR/data/fukuii-secondary"
mkdir -p "$SCRIPT_DIR/data/postgres"
mkdir -p "$SCRIPT_DIR/data/prometheus"
mkdir -p "$SCRIPT_DIR/data/grafana"
echo -e "${GREEN}✓ Directories created${NC}"
echo ""

# Generate random passwords if they're still default
echo -e "${YELLOW}Checking for default passwords...${NC}"
if grep -q "change_me_in_production" "$SCRIPT_DIR/.env" 2>/dev/null; then
    echo -e "${YELLOW}⚠ Warning: Default passwords detected in .env file${NC}"
    echo -e "${YELLOW}  Please update the following in .env:${NC}"
    echo -e "${YELLOW}  - KONG_ADMIN_PASSWORD${NC}"
    echo -e "${YELLOW}  - POSTGRES_PASSWORD${NC}"
    echo -e "${YELLOW}  - BASIC_AUTH_ADMIN_PASSWORD${NC}"
    echo -e "${YELLOW}  - BASIC_AUTH_DEV_PASSWORD${NC}"
    echo -e "${YELLOW}  - API_KEY_ADMIN${NC}"
    echo -e "${YELLOW}  - API_KEY_DEV${NC}"
    echo -e "${YELLOW}  - JWT_SECRET${NC}"
    echo ""
fi

# Check if kong.yml needs password updates
if grep -q "fukuii_admin_password" "$SCRIPT_DIR/kong.yml" 2>/dev/null; then
    echo -e "${YELLOW}⚠ Warning: Default passwords detected in kong.yml${NC}"
    echo -e "${YELLOW}  Please update consumer credentials in kong.yml${NC}"
    echo ""
fi

# Display configuration summary
echo -e "${GREEN}Configuration Summary:${NC}"
echo -e "  Docker Compose file: ${SCRIPT_DIR}/docker-compose.yml"
echo -e "  Kong config: ${SCRIPT_DIR}/kong.yml"
echo -e "  Prometheus config: ${SCRIPT_DIR}/prometheus/prometheus.yml"
echo -e "  Fukuii config: ${SCRIPT_DIR}/fukuii-conf/app.conf"
echo -e "  Data directories: ${SCRIPT_DIR}/data/"
echo ""

# Ask user if they want to start the stack
read -p "Do you want to start the Barad-dûr stack now? (y/n) " -n 1 -r
echo ""
if [[ $REPLY =~ ^[Yy]$ ]]; then
    echo -e "${YELLOW}Starting Barad-dûr (Kong API Gateway) stack...${NC}"
    cd "$SCRIPT_DIR"
    
    # Pull images first
    echo -e "${YELLOW}Pulling Docker images...${NC}"
    docker-compose pull
    
    # Start services
    echo -e "${YELLOW}Starting services...${NC}"
    docker-compose up -d
    
    echo ""
    echo -e "${GREEN}✓ Services started successfully!${NC}"
    echo ""
    
    # Wait for services to be healthy
    echo -e "${YELLOW}Waiting for services to be healthy (this may take a few minutes)...${NC}"
    sleep 10
    
    # Display service status
    echo ""
    echo -e "${GREEN}Service Status:${NC}"
    docker-compose ps
    
    echo ""
    echo -e "${GREEN}========================================${NC}"
    echo -e "${GREEN}Setup Complete!${NC}"
    echo -e "${GREEN}========================================${NC}"
    echo ""
    echo -e "${GREEN}Access URLs:${NC}"
    echo -e "  Kong Proxy:        http://localhost:8000"
    echo -e "  Kong Admin API:    http://localhost:8001"
    echo -e "  Prometheus:        http://localhost:9090"
    echo -e "  Grafana:           http://localhost:3000"
    echo -e "    Username: admin"
    echo -e "    Password: fukuii_grafana_admin"
    echo ""
    echo -e "${YELLOW}Next Steps:${NC}"
    echo -e "  1. Update passwords in .env and kong.yml"
    echo -e "  2. Test the API: curl -u admin:fukuii_admin_password http://localhost:8000/health"
    echo -e "  3. Check logs: docker-compose logs -f"
    echo -e "  4. Read the README.md for more information"
    echo ""
else
    echo -e "${YELLOW}Setup complete. To start the stack later, run:${NC}"
    echo -e "  cd $SCRIPT_DIR"
    echo -e "  docker-compose up -d"
    echo ""
fi

exit 0
