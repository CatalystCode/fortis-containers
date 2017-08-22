#!/usr/bin/env bash

readonly graphql_service_host="$1"
readonly site_name="$2"
readonly site_type="$3"

if ! (command -v python >/dev/null); then sudo apt-get install -y python; fi

cat << EOF | python | curl "${graphql_service_host}/api/settings" -d@- -H "Content-Type: application/json"
#
# parameters included from bash via variable interpolation
#
siteType = '${site_type}'
name = '${site_name}'

#
# python script to format graphql json request body
#
import json
import sys

query = '''mutation {
  createSite(
    input: {
      targetBbox: [],
      siteType: "%s",
      defaultZoomLevel: 8,
      logo: "",
      title: "",
      name: "%s",
      defaultLocation: [],
      supportedLanguages: ["en"]
    }
  ) { name }
}''' % (
  siteType,
  name,
)

payload = {
  'query': query,
}

print(json.dumps(payload))
EOF
