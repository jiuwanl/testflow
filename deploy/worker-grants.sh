#!/bin/sh
set -eu
export MYSQL_PWD="$(cat /run/secrets/mysql_root_password)"
mysql -hmysql -uroot <<'SQL'
GRANT INSERT,UPDATE ON testflow.tf_worker_node TO 'tf_worker'@'%';
GRANT UPDATE ON testflow.tf_test_run TO 'tf_worker'@'%';
GRANT UPDATE ON testflow.tf_run_item TO 'tf_worker'@'%';
GRANT INSERT ON testflow.tf_run_item_result TO 'tf_worker'@'%';
GRANT INSERT ON testflow.tf_outbox_event TO 'tf_worker'@'%';
SQL
