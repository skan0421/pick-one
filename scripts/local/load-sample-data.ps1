# Loads scripts/local/sample-data.sql into the local MariaDB container (pickone-mariadb).
# Local use only. Usage and details (Korean): app/README.md
#
# Notes
#  - This file is ASCII only on purpose. Windows PowerShell 5.1 reads a UTF-8 file without BOM
#    as the system code page, so non-ASCII text here could break the script.
#  - The SQL file is copied into the container instead of being piped through PowerShell.
#    A PowerShell 5.1 pipe to a native program re-encodes text and turns Korean into "?".
#  - DB account and password come from the container's own environment (MARIADB_USER, ...),
#    so this script does not read .env.

$ErrorActionPreference = 'Stop'

$container = 'pickone-mariadb'
$sqlFile = Join-Path $PSScriptRoot 'sample-data.sql'
$target = '/tmp/pickone-sample-data.sql'

$running = docker inspect -f '{{.State.Running}}' $container
if ($LASTEXITCODE -ne 0 -or $running -ne 'true') {
    Write-Host "Container '$container' is not running. Run 'docker compose up -d' first."
    exit 1
}

docker cp $sqlFile "${container}:$target"
if ($LASTEXITCODE -ne 0) { exit 1 }

# Single quotes: PowerShell passes the text as is, and the container's shell expands the variables.
docker exec $container sh -c 'MYSQL_PWD=$MARIADB_PASSWORD exec mariadb --default-character-set=utf8mb4 --table -u$MARIADB_USER $MARIADB_DATABASE < /tmp/pickone-sample-data.sql'
if ($LASTEXITCODE -ne 0) {
    Write-Host 'Failed to load sample data. Has the server been started once so that Flyway created the tables?'
    exit 1
}

Write-Host 'Sample data loaded. Expected: members 3 (hex EC8398), questions_text 16, questions_image 8, options 60, boosted 2'
