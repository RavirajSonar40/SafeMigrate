#!/bin/bash
set -e

# 1. Setup Swap if not already active
if [ ! -f /swapfile ]; then
  echo "Creating 3GB swapfile..."
  sudo fallocate -l 3G /swapfile
  sudo chmod 600 /swapfile
  sudo mkswap /swapfile
  sudo swapon /swapfile
  echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
fi

# 2. Update packages and install Docker
echo "Installing Docker & Docker Compose..."
sudo apt-get update -y
sudo apt-get install -y docker.io docker-compose-v2 git
sudo usermod -aG docker ubuntu

# 3. Clone or pull repo
if [ ! -d "SafeMigrate" ]; then
  echo "Cloning SafeMigrate..."
  git clone https://github.com/RavirajSonar40/SafeMigrate.git
else
  echo "Pulling SafeMigrate..."
  cd SafeMigrate && git pull origin main && cd ..
fi

free -h
docker --version
docker compose version
