#!/usr/bin/env bash

set -euxo pipefail

apt-get update
apt-get dist-upgrade -y
apt-get install -y curl bzip2 xz-utils locales
locale-gen en_US.UTF-8
update-locale LC_ALL=en_US.UTF-8 LANG=en_US.UTF-8

adduser --disabled-password --gecos '' fukuii

mkdir /nix
chown fukuii:fukuii /nix
su fukuii -c 'curl -L https://nixos.org/nix/install | sh \
              && tail -n 1 ~/.profile >> ~/.bashrc'
ln -s /home/fukuii/fukuii-dist/app /app

apt-get purge -y curl bzip2
apt-get clean -y
rm -rf /var/cache/debconf/* /var/lib/apt/lists/* /var/log/* /tmp/* /var/tmp/*