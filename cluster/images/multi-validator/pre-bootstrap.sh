#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -eou pipefail

source /app/tools.sh

function request_onboarding_secret() {
    MAX_RETRY=100
    n=0

    if [[ -z ${CN_APP_DEVNET:-} ]]; then
        json_log "multi-validator is only supported in devnet mode." >&2
        exit 1
    else
        ONBOARD_SECRET_URL="${CN_APP_VALIDATOR_SV_SPONSOR_ADDRESS}/api/sv/v0/devnet/onboard/validator/prepare"

        json_log "Getting onboarding secret from SV (${ONBOARD_SECRET_URL})..." "pre-bootstrap.sh" >&2

        until [ $n -gt $MAX_RETRY ]; do

            if SECRET=$(curl -sSfL -X POST "${ONBOARD_SECRET_URL}" 2> /dev/null); then
                echo "$SECRET"
                break
            else
                json_log "Failed to get onboarding secret. Retrying in 1 second..." "pre-bootstrap.sh" >&2
                n=$((n+1))
                sleep 1
            fi
            if [ $n -gt $MAX_RETRY ]; then
                json_log "Getting onboarding secret exceeded max retries" "pre-bootstrap.sh" >&2
                exit 1
            fi
        done
    fi
}


function write_validator_config() {
    local index
    local secret
    index="$(printf %02d "$1")"

    local base_port=$(( 5000 + ( $1 * 100 ) ))

    local participant_ledger_port=$(( base_port + 1 ))
    local participant_admin_port=$(( base_port + 2 ))
    local validator_admin_port=$(( base_port + 3 ))

    local user="${VALIDATOR_USERNAME_PREFIX}_${index}"

    secret="$(request_onboarding_secret)"

    cat <<EOF >> /app/app.conf
canton.validator-apps.validator_backend_$index = {
    app-instances = {}

    scan-client = {
        type = "bft"
        seed-urls = [
            \${CN_APP_VALIDATOR_SCAN_URL}
        ]
    }

    storage = {
        type = postgres
        config {
            dataSourceClass = "org.postgresql.ds.PGSimpleDataSource"
            properties = {
                databaseName = \${CN_APP_POSTGRES_DATABASE_NAME}_$index
                currentSchema = \${CN_APP_POSTGRES_SCHEMA}_validator_$index
                serverName = \${CN_APP_POSTGRES_HOST}
                portNumber = \${CN_APP_POSTGRES_PORT}
                user = \${CN_APP_POSTGRES_USER}
                password = \${CN_APP_POSTGRES_PASSWORD}
            }
        }
    }

    admin-api = {
        address = "0.0.0.0"
        port = $validator_admin_port
    }

    participant-client = {
        admin-api = {
            address = \${CN_APP_VALIDATOR_PARTICIPANT_ADDRESS}
            port = $participant_admin_port
        }
        ledger-api = {
            client-config = {
                address = \${CN_APP_VALIDATOR_PARTICIPANT_ADDRESS}
                port = $participant_ledger_port
            }
            auth-config = {
                type = "self-signed"
                user = $user
                secret = "test"
                # TODO(#2052) use actual audience of the target participant
                audience = "https://canton.network.global"
                audience = \${?CN_APP_VALIDATOR_LEDGER_API_AUTH_AUDIENCE}
            }
        }
    }

    ledger-api-user = $user
    validator-wallet-user = $user
    validator-party-hint = $user

    auth {
        algorithm = "hs-256-unsafe"
        audience = "https://canton.network.global"
        audience = \${?CN_APP_VALIDATOR_AUTH_AUDIENCE}
        secret = "test"
    }

    onboarding = {
        sv-client.admin-api.url = \${?CN_APP_VALIDATOR_SV_SPONSOR_ADDRESS}
        secret = "$secret"
    }

    domains {
        global {
            alias = "global"
            buy-extra-traffic {
              target-throughput: 1000000
              min-topup-interval: 1m
            }
        }
    }

    domain-migration-id =\${CN_APP_VALIDATOR_MIGRATION_ID}

    sv-validator = \${?CN_APP_VALIDATOR_SV_VALIDATOR}

    contact-point = \${CN_APP_CONTACT_POINT}
}
EOF
}

nodes=${NUM_NODES:-1}
for i in $( seq 0 $(( nodes - 1 )) )
do
    write_validator_config "$i"
done
