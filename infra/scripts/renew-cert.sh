#!/bin/bash
set -e
cd /home/ubuntu/axcore
echo "== $(date '+%F %T') renew start"
docker compose run --rm -T certbot renew --quiet
docker compose exec -T nginx nginx -s reload
echo "== $(date '+%F %T') renew done"
