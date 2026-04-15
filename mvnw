#!/bin/sh
BASEDIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
exec mvn "$@"
