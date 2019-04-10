#!/usr/bin/env bash

set -eux

# Starting from scratch...
git clean -xdf

psql -c 'create database test_db;' -U postgres

# Run tests that use a db
mill -i contrib.flyway.test
