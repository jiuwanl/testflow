#!/bin/sh
set -eu
export MYSQL_PWD="$(cat /run/secrets/mysql_root_password)"
api_password=$(cat /run/secrets/api_password)
worker_password=$(cat /run/secrets/worker_password)
migration_password=$(cat /run/secrets/migration_password)
# Generated hex only. Never trace or print this script's expanded SQL.
for value in "$api_password" "$worker_password" "$migration_password"; do
  case "$value" in ''|*[!0-9a-f]*) echo 'Invalid generated credential' >&2; exit 1;; esac
done
mysql -hmysql -uroot <<SQL
CREATE DATABASE IF NOT EXISTS testflow CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER IF NOT EXISTS 'tf_migrator'@'%' IDENTIFIED BY '$migration_password';
CREATE USER IF NOT EXISTS 'tf_api'@'%' IDENTIFIED BY '$api_password';
CREATE USER IF NOT EXISTS 'tf_worker'@'%' IDENTIFIED BY '$worker_password';
GRANT ALL PRIVILEGES ON testflow.* TO 'tf_migrator'@'%';
GRANT SELECT,INSERT,UPDATE,DELETE ON testflow.* TO 'tf_api'@'%';
GRANT SELECT ON testflow.* TO 'tf_worker'@'%';
SQL
